package icons

import com.intellij.openapi.util.IconLoader

object MirrordIcons {
    @JvmField
    val usage = IconLoader.getIcon("/icons/usage.png", javaClass)

    @JvmField
    val enabled = IconLoader.getIcon("/icons/mirrord_enabled.svg", javaClass)

    @JvmField
    val disabled = IconLoader.getIcon("/icons/mirrord_disabled.svg", javaClass)
}
