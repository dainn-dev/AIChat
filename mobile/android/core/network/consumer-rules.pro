# AIChat :core:network
# Retrofit 3 and kotlinx.serialization ship their own R8 rules. The DTOs are kept explicitly as cheap insurance,
# because a stripped field silently breaks the wire contract with the backend.
-keep class com.aichat.keyboard.core.network.model.** { *; }
