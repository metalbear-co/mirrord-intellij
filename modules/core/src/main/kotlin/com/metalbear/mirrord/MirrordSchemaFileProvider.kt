package com.metalbear.mirrord
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion

/**
 * Gives the IDE the schema of mirrord config files.
 *
 * The schema comes from [MirrordConfigSchema]. It is loaded the first time that the IDE asks if a
 * mirrord config file has a schema.
 */
class MirrordSchemaFileProvider(private val project: Project) : JsonSchemaFileProvider {

    override fun isAvailable(file: VirtualFile): Boolean {
        val path = file.path
        val available = MirrordConfigAPI.isConfigFilePath(file) && path.endsWith(".json")
        if (available) {
            project.service<MirrordConfigSchema>().markNeeded()
        }
        return available
    }

    override fun getName(): String {
        return "mirrord"
    }

    override fun getSchemaFile(): VirtualFile {
        return project.service<MirrordConfigSchema>().schemaFile()
    }

    override fun getSchemaType(): SchemaType {
        return when (project.service<MirrordConfigSchema>().source) {
            MirrordConfigSchemaSource.GitHub -> SchemaType.remoteSchema
            else -> SchemaType.embeddedSchema
        }
    }

    override fun getRemoteSource(): String? {
        return when (project.service<MirrordConfigSchema>().source) {
            MirrordConfigSchemaSource.GitHub -> GITHUB_CONFIG_SCHEMA_URL
            else -> null
        }
    }

    override fun getSchemaVersion(): JsonSchemaVersion {
        return JsonSchemaVersion.SCHEMA_7
    }
}
