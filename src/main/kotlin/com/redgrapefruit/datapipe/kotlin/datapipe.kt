package com.redgrapefruit.datapipe.kotlin

import net.fabricmc.fabric.api.event.EventFactory
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resource.ResourceManager
import net.minecraft.resource.ResourceType
import net.minecraft.util.Identifier
import org.jetbrains.annotations.ApiStatus
import java.io.InputStream
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

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
    /**
     * Returns a resource from this [Pipeline]. Nullable.
     *
     * Using a property with a [fetchFromPipeline] delegate is preferred for most of the time.
     */
    fun get(id: Identifier): T? = contents[id]

    /**
     * A less safe version of [get] that throws a [NullPointerException] if not found
     */
    fun getOrThrow(id: Identifier): T = get(id) ?: throw NullPointerException("Resource not found: $id")

    internal fun clear(): Unit = contents.clear()

    internal fun put(id: Identifier, value: Any) {
        contents[id] = value as T
    }

    companion object {
        /**
         * Creates a new instance of a [Builder]
         */
        fun <T> builder() = Builder<T>()

        /**
         * A helpful utility to create a [Pipeline] filter by a specific extension
         */
        @Deprecated("Please use Builder.filterByExtension")
        @ApiStatus.ScheduledForRemoval(inVersion = "1.3")
        fun filterByExtension(extension: String): (String) -> Boolean {
            return { name -> name.endsWith(extension) }
        }
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
 * Allows for registering [Pipeline]s into the system
 */
object Pipelines {
    private val pipelines: MutableMap<Identifier, Pipeline<*>> = mutableMapOf()

    /**
     * Registers a [Pipeline] under an [Identifier]
     */
    fun register(id: Identifier, pipeline: Pipeline<*>) {
        pipelines[id] = pipeline
    }

    internal fun get(id: Identifier): Pipeline<*> {
        return pipelines[id]!!
    }
}

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
 * A [PipelineFetchDelegate] is used to actually **use** a [Pipeline] resource in your code after it's loaded.
 *
 * Example:
 * ```kotlin
 * val starConfig by fetchFromPipeline(ModPipelines.STAR_CONFIG, new Identifier("example_mod", "name_of_my_star_config"))
 * ```
 *
 * This delegate is created via the [fetchFromPipeline] helper.
 *
 * **DANGER:** be careful when fetching a [Pipeline] resource, since **it will only be available after world-load**.
 * Any login/auth/menu resources are inherently _impossible_ using the built-in Minecraft system.
 */
private class PipelineFetchDelegate<T>(
    private val pipeline: Pipeline<T>,
    private val id: Identifier) : ReadOnlyProperty<Any, T> {

    override fun getValue(thisRef: Any, property: KProperty<*>): T {
        return pipeline.get(id) ?: throw RuntimeException("Tried to access resource $id too early!")
    }
}

/**
 * Creates an instance of [PipelineFetchDelegate]
 */
fun <T> fetchFromPipeline(pipeline: Pipeline<T>, id: Identifier): ReadOnlyProperty<Any, T> {
    return PipelineFetchDelegate(pipeline, id)
}
