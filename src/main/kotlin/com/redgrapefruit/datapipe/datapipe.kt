package com.redgrapefruit.datapipe

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.event.EventFactory
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resource.ResourceManager
import net.minecraft.resource.ResourceType
import net.minecraft.util.Identifier
import java.io.InputStream

/**
 * The [PipeResourceLoader] is a base loader for all pipeline resources.
 */
abstract class PipeResourceLoader<T> : SimpleSynchronousResourceReloadListener {
    /**
     * The [Pipeline] for this type of resource
     */
    abstract val pipeline: Pipeline<T>

    override fun getFabricId(): Identifier = pipeline.id // inherit the id from the pipeline

    override fun reload(manager: ResourceManager) {
        pipeline.clear() // clear the caches

        val resources = manager.findResources(pipeline.resourceFolder, pipeline.filter)
        resources.forEach { resourceId ->
            val output = load(manager.getResource(resourceId).inputStream, resourceId) // load

            pipeline.put(output.id, output.value) // add to pipeline
            ResourceLoadCallback.EVENT.invoker().onLoad(pipeline, output.value, output.id) // invoke event
        }
    }

    /**
     * Load the resource from a raw [InputStream] and put the resource ID and object into a [PipelineOutput]
     */
    abstract fun load(stream: InputStream, id: Identifier): PipelineOutput

    protected fun readStreamContent(stream: InputStream): String {
        stream.use {
            return stream.readBytes().decodeToString()
        }
    }

    // Registering
    companion object {
        /**
         * Registers a [PipeResourceLoader] operating on client resources
         */
        fun <T> registerClient(loader: PipeResourceLoader<T>) {
            ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(loader)
        }

        /**
         * Registers a [PipeResourceLoader] operating on server data
         */
        fun <T> registerServer(loader: PipeResourceLoader<T>) {
            ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(loader)
        }
    }
}

/**
 * A built-in [PipeResourceLoader] for loading in JSON data with `kotlinx.serialization`.
 */
open class JsonResourceLoader<T : Any>(
    private val mod: String,
    private val serializer: DeserializationStrategy<T>,
    override val pipeline: Pipeline<T>
) : PipeResourceLoader<T>() {

    override fun load(stream: InputStream, id: Identifier): PipelineOutput {
        val content = readStreamContent(stream)
        val obj: T = Json.decodeFromString(serializer, content)
        val name = parseName(id)

        return PipelineOutput(obj, Identifier(mod, name))
    }

    open fun parseName(id: Identifier): String {
        return id.toString().replace("$mod:${pipeline.resourceFolder}/", "").replace(".json", "")
    }
}

/**
 * A [Pipeline] is a general configuration for a type of resource.
 *
 * A [Builder] is preferred over standard creation (even though it's still an option),
 * see [builder] for creating the instance of a [Builder].
 */
data class Pipeline<T>(
    /**
     * The registry [Identifier] for this pipeline
     */
    val id: Identifier,
    /**
     * The name of the folder in the pack with this type of resource
     */
    val resourceFolder: String,
    /**
     * The filter for the files of this resource.
     *
     * If you want to filter by extension (for example, `.json`), please use the [filterByExtension] helper
     */
    val filter: (String) -> Boolean,

    internal val contents: MutableMap<Identifier, T> = mutableMapOf()
) {
    internal fun clear(): Unit = contents.clear()

    internal fun put(id: Identifier, value: Any) {
        contents[id] = value as T
    }

    /**
     * Returns a [ResourceHandle] for a resource at a given [id] from this pipeline.
     */
    fun resource(id: Identifier) = ResourceHandle(this, id)

    companion object {
        /**
         * Creates a new instance of a [Builder]
         */
        fun <T> builder() = Builder<T>()
    }

    /**
     * A builder for [Pipeline]s. Preferred to use in most cases
     */
    class Builder<T> internal constructor() {
        private var id: Identifier? = null
        private var resourceFolder: String? = null
        private var filter: ((String) -> Boolean)? = null

        fun underId(id: Identifier): Builder<T> {
            this.id = id
            return this
        }

        fun storedIn(resourceFolder: String): Builder<T> {
            this.resourceFolder = resourceFolder
            return this
        }

        fun filter(filter: (String) -> Boolean): Builder<T> {
            this.filter = filter
            return this
        }

        fun filterByExtension(extension: String): Builder<T> {
            this.filter = { name -> name.endsWith(extension) }
            return this
        }

        fun noFilter(): Builder<T> {
            this.filter = { _ -> true }
            return this
        }

        fun build(): Pipeline<T> = Pipeline(id!!, resourceFolder!!, filter!!)
    }
}

/**
 * Represents the output of loading a [Pipeline] resource
 */
data class PipelineOutput(
    /**
     * The actual value of the resource
     */
    val value: Any,
    /**
     * The unique [Identifier] of this resource under which you'll be able to obtain it later on
     */
    val id: Identifier
)

/**
 * [ResourceLoadCallback] provides the ability for you to post-process a [Pipeline] resource right after its load.
 */
interface ResourceLoadCallback {
    fun onLoad(pipeline: Pipeline<*>, resource: Any, id: Identifier)

    companion object {
        internal val EVENT = EventFactory.createArrayBacked(ResourceLoadCallback::class.java)
        { listeners ->
            Impl { pipeline, resource, id ->
                listeners.forEach { listener -> listener.onLoad(pipeline, resource, id) }
            }
        }

        /**
         * Registers a listener for this event
         */
        fun register(action: (Pipeline<*>, Any, Identifier) -> Unit) {
            EVENT.register(Impl(action))
        }
    }

    private class Impl(private val implementation: (Pipeline<*>, Any, Identifier) -> Unit) : ResourceLoadCallback {
        override fun onLoad(pipeline: Pipeline<*>, resource: Any, id: Identifier) = implementation(pipeline, resource, id)
    }
}

/**
 * A [ResourceHandle] provides a safe way of accessing resources.
 */
class ResourceHandle<T>(
    @PublishedApi internal val pipeline: Pipeline<T>,
    @PublishedApi internal val id: Identifier
) {
    /**
     *  Returns the resource value, without any guarantees it's not `null`
     */
    fun tryGet(): T? {
        return pipeline.contents[id]
    }

    /**
     * Tries to return the resource value, else throws an [ResourceAccessedEarlyException].
     *
     * **Avoid this in favor of [ifAvailable], if possible!**
     */
    fun getOrThrow(): T {
        return pipeline.contents[id] ?: throw ResourceAccessedEarlyException("$id isn't available yet!")
    }

    /**
     * Tries to get the resource value, if it's `null`, calls the given function.
     *
     * Even when the function, is called **there's no not-`null` guarantee**, so the resource could still be null!
     * Be careful!
     */
    inline fun getOrDo(action: () -> Unit): T? {
        val value = tryGet()

        if (value == null) action.invoke()

        return value
    }

    /**
     * Tries to get the resource value, if it's `null`, obtains the value from the given function's result.
     */
    inline fun getOrUse(action: () -> T): T {
        var value = tryGet()

        if (value == null) value = action.invoke()

        return value!!
    }

    /**
     * Tries to get the resource value, if it's `null`, uses the given [fallback] value.
     */
    fun getOrUse(fallback: T): T {
        return getOrUse { fallback }
    }

    /**
     * Returns if the resource is currently available
     */
    fun isAvailable(): Boolean {
        return pipeline.contents.contains(id)
    }

    /**
     * **Preferred resource-handling method**.
     *
     * If the resource is available, performs some operation with it.
     */
    inline fun ifAvailable(action: (T) -> Unit) {
        if (isAvailable()) action.invoke(getOrThrow())
    }

    /**
     * Opposite of [ifAvailable], performs some operation if the resource is currently unavailable.
     */
    inline fun ifUnavailable(action: () -> Unit) {
        if (!isAvailable()) action.invoke()
    }

    companion object {
        /** A wrapper for the class's constructor. */
        fun <T> of(pipeline: Pipeline<T>, id: Identifier) = ResourceHandle(pipeline, id)
    }
}

class ResourceAccessedEarlyException(msg: String) : RuntimeException(msg)
