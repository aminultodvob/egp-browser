# Strip verbose/debug/info logging from release builds as a second line of defence
# behind SafeLog (which already logs only in debug builds).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
