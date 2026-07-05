package top.yukonga.scripta.editor

/**
 * Name of the current platform. Present in the scaffold to prove the expect/actual triangle
 * (commonMain → androidMain/desktopMain) compiles and links. The real platform seam will be the
 * self-managed IME on Android vs. the simplified input path on desktop.
 */
internal expect fun editorPlatformName(): String
