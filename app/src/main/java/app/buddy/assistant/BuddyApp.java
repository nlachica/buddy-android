package app.buddy.assistant;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

/** The app's context, for code with no Context at hand (static helpers) that needs a string in the user's language. */
public class BuddyApp extends Application {
    private static Context ctx;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = this;
        A11yWatch.processStarted(this);
        // "hey buddy" starts when any Buddy screen resumes: a microphone grant given from a chat or Settings ends here
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) { WakeWord.ensure(a); }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    static Context context() {
        return ctx;
    }

    /** String resource id in the app's language, formatted with args if there are any. */
    static String str(int id, Object... args) {
        return args.length == 0 ? ctx.getString(id) : ctx.getString(id, args);
    }

    /** Plural resource id for the count n, formatted with args (usually n itself). */
    static String plural(int id, int n, Object... args) {
        return ctx.getResources().getQuantityString(id, n, args);
    }
}
