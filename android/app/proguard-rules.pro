# sshj picks ciphers, key exchanges and signatures by name through JCA, and BouncyCastle
# registers its algorithms as class-name strings. R8 cannot see those references, so keep
# both libraries whole rather than chase individual algorithms.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }

# Optional integrations that sshj and BouncyCastle probe for at runtime.
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn com.jcraft.jzlib.**

# Keep line numbers so crash reports from release builds point at real code.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
