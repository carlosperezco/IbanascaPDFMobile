# Clases y miembros que usa el código C++ (JNI) por su nombre.
-keep class com.donatellatech.ibanascapdf.Nucleo { native <methods>; }
-keep class com.donatellatech.ibanascapdf.ErrorNucleo {
    <init>(java.lang.String, java.lang.String);
    java.lang.String getClave();
}
-keep class com.donatellatech.ibanascapdf.FirmanteAndroid {
    public static byte[][] certificados(java.lang.String, java.lang.String);
    public static byte[] firmar(java.lang.String, java.lang.String, byte[]);
}
# El visor se crea desde el XML.
-keep class com.donatellatech.ibanascapdf.VisorPdf { <init>(android.content.Context, android.util.AttributeSet); }
