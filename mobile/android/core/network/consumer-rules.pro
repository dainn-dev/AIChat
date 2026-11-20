# AIChat :core:network - R8 rules applied to the consuming app.
#
# Retrofit 3 and kotlinx.serialization ship their own rules, but this module keeps everything that crosses the
# wire explicitly instead of relying on them (or on the app's rules): a stripped or renamed field silently breaks
# the contract with the backend, or loses the stored session.

# Request/response DTOs, their generated serializers and companions.
-keep class com.aichat.keyboard.core.network.model.** { *; }

# The persisted session (encrypted JSON in session.bin).
-keep class com.aichat.keyboard.core.network.auth.AuthTokens { *; }
-keep class com.aichat.keyboard.core.network.auth.AuthTokens$* { *; }

# The backend error envelope.
-keep class com.aichat.keyboard.core.network.error.ErrorEnvelope { *; }
-keep class com.aichat.keyboard.core.network.error.ErrorEnvelope$* { *; }
-keep class com.aichat.keyboard.core.network.error.ErrorBody { *; }
-keep class com.aichat.keyboard.core.network.error.ErrorBody$* { *; }

# Retrofit service interfaces (their annotations and generic signatures are read reflectively).
-keep,allowobfuscation interface com.aichat.keyboard.core.network.api.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
