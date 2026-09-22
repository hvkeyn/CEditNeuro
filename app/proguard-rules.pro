# JGit relies on reflection and ships optional integrations we do not use.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn org.apache.sshd.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.hvkeyn.ceditneuro.** {
    *** Companion;
}
-keepclasseswithmembers class com.hvkeyn.ceditneuro.** {
    kotlinx.serialization.KSerializer serializer(...);
}
