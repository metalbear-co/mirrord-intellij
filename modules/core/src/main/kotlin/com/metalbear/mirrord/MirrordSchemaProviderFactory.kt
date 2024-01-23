package com.metalbear.mirrord

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory

class MirrordSchemaProviderFactory : JsonSchemaProviderFactory, DumbAware {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> {
        return listOf(MirrordSchemaFileProvider())
    }

}