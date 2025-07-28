package io.github.tehcneko.hyperinstaller;

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

@SuppressLint("PrivateApi")
public class Hooker extends XposedModule {

    private static final String TAG = "HyperInstaller";
    private static Field fIsInternationalBuildBoolean;

    public Hooker(@NonNull XposedInterface base, @NonNull ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onSystemServerLoaded(@NonNull SystemServerLoadedParam param) {
        var classLoader = param.getClassLoader();
        try {
            var PackageManagerServiceStubClass = classLoader.loadClass("com.android.server.pm.PackageManagerServiceStub");
            fIsInternationalBuildBoolean = PackageManagerServiceStubClass.getDeclaredField("IS_INTERNATIONAL_BUILD");
            fIsInternationalBuildBoolean.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            log("Failed to find IS_INTERNATIONAL_BUILD field", e);
        }
        try {
            hookPackageManagerServiceImpl(classLoader);
        } catch (Throwable t) {
            log("Failed to hook PackageManagerServiceImpl", t);
        }
        try {
            hookIsCTS(classLoader);
        } catch (Throwable t) {
            log("Failed to hook isCTS", t);
        }
    }

    private void hookPackageManagerServiceImpl(ClassLoader classLoader) throws ClassNotFoundException {
        var packageManagerServiceImpl = classLoader.loadClass("com.android.server.pm.PackageManagerServiceImpl");
        var methods = packageManagerServiceImpl.getDeclaredMethods();
        for (var method : methods) {
            var name = method.getName();
            if ("hookChooseBestActivity".equals(name) ||
                    "updateDefaultPkgInstallerLocked".equals(name) ||
                    "assertValidApkAndInstaller".equals(name)) {
                Log.d(TAG, "hooking method " + name);
                hook(method, PackageManagerServiceImplHooker.class);
                if ("hookChooseBestActivity".equals(name)) {
                    hook(method, ChooseBestActivityHooker.class);
                }
                deoptimize(method);
            }
        }
    }

    private void hookIsCTS(ClassLoader classLoader) throws NoSuchMethodException, ClassNotFoundException {
        var packageManagerServiceImpl = classLoader.loadClass("com.android.server.pm.PackageManagerServiceImpl");
        var isCTSMethod = packageManagerServiceImpl.getDeclaredMethod("isCTS");
        hook(isCTSMethod, IsCTSHooker.class);
    }

    @XposedHooker
    private static class IsCTSHooker implements Hooker {
        public static boolean fakeCTS = false;

        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) throws Throwable {
            if (fakeCTS) {
                callback.returnAndSkip(true);
            }
        }
    }

    @XposedHooker
    private static class PackageManagerServiceImplHooker implements Hooker {

        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) throws Throwable {
            IsCTSHooker.fakeCTS = true;
        }

        @AfterInvocation
        public static void after(@NonNull AfterHookCallback callback) throws Throwable {
            IsCTSHooker.fakeCTS = false;
        }
    }

    @XposedHooker
    private static class ChooseBestActivityHooker implements Hooker {

        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) throws Throwable {
            if (fIsInternationalBuildBoolean != null) {
                fIsInternationalBuildBoolean.setBoolean(null, true);
            }
        }

        @AfterInvocation
        public static void after(@NonNull AfterHookCallback callback) throws Throwable {
            if (fIsInternationalBuildBoolean != null) {
                fIsInternationalBuildBoolean.setBoolean(null, false);
            }
        }
    }
}
