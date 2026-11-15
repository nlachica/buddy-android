package app.buddy.assistant;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Shares single files with other apps by URI permission: the camera writes the new photo to
 * content://…/capture, and attachments open in a viewer via content://…/a/&lt;id&gt;.
 */
public class FilesProvider extends ContentProvider {
    static final String AUTHORITY = "app.buddy.assistant.files";

    static Uri captureUri() {
        return Uri.parse("content://" + AUTHORITY + "/capture");
    }

    static Uri attachmentUri(String id) {
        return Uri.parse("content://" + AUTHORITY + "/a/" + id);
    }

    /** The installer opens this URI after the tool has returned, so each install attempt gets a file and URI of its own. */
    static Uri apkUri(File staged) {
        return Uri.parse("content://" + AUTHORITY + "/apk/" + staged.getName());
    }

    /**
     * A new file for an APK on its way to Android's installer (install_apk, update_buddy). Staged APKs older than a day
     * go; a newer one may still be waiting for the installer, so it stays.
     */
    static File newApk(File cacheDir) throws java.io.IOException {
        File dir = new File(cacheDir, "apk");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (f.lastModified() < System.currentTimeMillis() - 24 * 3600_000L) f.delete();
        return File.createTempFile("install", ".apk", dir);
    }

    /** The staged APK a URI name stands for, or null for a name that isn't one. */
    static File apkFor(File cacheDir, String name) {
        return name.contains("/") || name.startsWith(".") ? null : new File(new File(cacheDir, "apk"), name);
    }

    /** A file from Buddy's Linux handed to the share sheet (share): a copy, by its name. */
    static File shareFile(android.content.Context c, String name) {
        File dir = new File(c.getCacheDir(), "share");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete(); // one share at a time
        dir.mkdirs();
        return new File(dir, name);
    }

    static Uri shareUri(String name) {
        return Uri.parse("content://" + AUTHORITY + "/share/" + Uri.encode(name));
    }

    static File captureFile(android.content.Context c) {
        return new File(c.getCacheDir(), "capture.jpg");
    }

    private File fileFor(Uri uri) {
        java.util.List<String> p = uri.getPathSegments();
        if (p.size() == 1 && "capture".equals(p.get(0))) return captureFile(getContext());
        if (p.size() == 2 && "a".equals(p.get(0))) return Attachments.file(getContext(), p.get(1));
        if (p.size() == 2 && "apk".equals(p.get(0))) return apkFor(getContext().getCacheDir(), p.get(1));
        if (p.size() == 2 && "share".equals(p.get(0)) && !p.get(1).contains("/") && !p.get(1).startsWith(".")) {
            return new File(new File(getContext().getCacheDir(), "share"), p.get(1));
        }
        return null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (f == null) throw new FileNotFoundException(uri.toString());
        boolean capture = f.equals(captureFile(getContext()));
        if (!capture && !"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(capture ? mode : "r"));
    }

    @Override
    public String getType(Uri uri) {
        File f = fileFor(uri);
        if (f == null) return null;
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")) return "text/plain";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
        File f = fileFor(uri);
        if (f == null) return null;
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        String name = f.getParentFile() != null && "apk".equals(f.getParentFile().getName()) ? "app.apk"
                : f.getParentFile() != null && "share".equals(f.getParentFile().getName()) ? f.getName()
                : f.getParentFile() != null && "attachments".equals(f.getParentFile().getName())
                ? Attachments.name(f.getName()) : "photo.jpg";
        c.addRow(new Object[]{name, f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
