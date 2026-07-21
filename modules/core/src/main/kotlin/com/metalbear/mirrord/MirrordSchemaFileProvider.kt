package com.metalbear.mirrord
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion
import com.jetbrains.jsonSchema.remote.JsonFileResolver

class MirrordSchemaFileProvider : JsonSchemaFileProvider {
    override fun isAvailable(file: VirtualFile): Boolean {
        val path = file.path
        return MirrordConfigAPI.isConfigFilePath(file) && path.endsWith(".json")
    }

    override fun getName(): String = "mirrord"

    override fun getSchemaFile(): VirtualFile? = JsonFileResolver.urlToFile(this.remoteSource)

    override fun getSchemaType(): SchemaType = SchemaType.remoteSchema

    override fun getRemoteSource(): String = "https://raw.githubusercontent.com/metalbear-co/mirrord/latest/mirrord-schema.json"

    override fun getSchemaVersion(): JsonSchemaVersion = JsonSchemaVersion.SCHEMA_7
}
