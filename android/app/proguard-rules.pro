# kotlinx.serialization：保留生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }

# 类型安全导航：路由参数里的枚举（Page 等）按完整类名查找，不能被改名
-keep enum app.qichi.navigation.** { *; }
