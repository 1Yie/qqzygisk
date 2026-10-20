package com.qm.qqzygisk.saf;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Real DocumentsProvider living in the androidTest APK.
 *
 * <p>The androidTest APK is installed as its own package/UID, so the target app under test can
 * only touch these documents through a URI grant obtained from the system DocumentsUI.
 *
 * <p>Implemented in Java on purpose: the fixture provider runs in the test APK's own process,
 * where the Kotlin stdlib may not be packaged (AGP de-duplicates it into the target APK).
 *
 * <p>Roots:
 * <ul>
 *   <li>{@link #ROOT_IMAGES} writable, PNG + two-frame GIF + sub folder + protected dir;</li>
 *   <li>{@link #ROOT_READONLY} read-only documents;</li>
 *   <li>{@link #ROOT_FUNBOX} writable FunBox tree with one pack, for migration tests.</li>
 * </ul>
 */
public class SafFixtureProvider extends DocumentsProvider {

    public static final String AUTHORITY = "com.qm.qqzygisk.saf.fixture";

    public static final String ROOT_IMAGES = "images";
    public static final String ROOT_READONLY = "readonly";
    public static final String ROOT_FUNBOX = "funbox";
    public static final String ROOT_IMAGES_TITLE = "SAF Fixture Images";
    public static final String ROOT_READONLY_TITLE = "SAF Fixture Readonly";
    public static final String ROOT_FUNBOX_TITLE = "SAF Fixture FunBox";

    public static final String PNG_NAME = "alpha.png";
    public static final String GIF_NAME = "anim.gif";
    public static final String SUB_FOLDER = "Stickers";
    public static final String SUB_IMAGE_NAME = "beta.png";
    public static final String READONLY_PNG_NAME = "gamma.png";
    /** Writable directory whose own deletion is not supported by the provider. */
    public static final String PROTECTED_FOLDER = "Protected";
    public static final String PROTECTED_IMAGE_NAME = "guard.png";
    public static final String FUNBOX_PACK = "TestPack";
    public static final String FUNBOX_IMAGE_NAME = "sticker.png";

    /**
     * Genuinely two-frame 1x1 GIF89a (black frame, then white frame) so the production
     * animated-image path can be exercised. Deterministic bytes.
     */
    private static final String TWO_FRAME_GIF_HEX =
            "47494638396101000100800000000000FFFFFF"
                    + "21FF0B4E45545343415045322E300301000000"
                    + "21F904040A0000002C0000000001000100000202440100"
                    + "21F904040A0000002C00000000010001000002024C0100"
                    + "3B";

    private static final String[] DEFAULT_ROOT_COLUMNS = {
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_CAPACITY_BYTES,
    };

    private static final String[] DEFAULT_DOCUMENT_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
    };

    private File fixtureDir;
    private boolean initialized;

    public static Uri documentUri(Context context, String documentId) {
        return DocumentsContract.buildDocumentUri(AUTHORITY, documentId);
    }

    public static Uri rootImagesUri(Context context) {
        return documentUri(context, ROOT_IMAGES);
    }

    public static Uri rootReadonlyUri(Context context) {
        return documentUri(context, ROOT_READONLY);
    }

    /** Tree URI, the same shape ACTION_OPEN_DOCUMENT_TREE returns for a selected root. */
    public static Uri rootTreeUri(String rootId) {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, rootId);
    }

    /** Root document addressed through its tree, valid for DocumentsUI EXTRA_INITIAL_URI. */
    public static Uri rootInitialUri(String rootId) {
        return DocumentsContract.buildDocumentUriUsingTree(rootTreeUri(rootId), rootId);
    }

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        fixtureDir = new File(context.getFilesDir(), "saf-fixture");
        seedAtStartup();
        initialized = true;
        return true;
    }

    private File imagesDir() {
        return new File(fixtureDir, ROOT_IMAGES);
    }

    private File readonlyDir() {
        return new File(fixtureDir, ROOT_READONLY);
    }

    private File funboxDir() {
        return new File(fixtureDir, ROOT_FUNBOX);
    }

    /**
     * Startup-only seeding. Known seed inputs are rewritten once per provider process so an
     * upgraded or previously failed APK fixture is reset, while test-created documents are
     * left untouched. Later operations must expose the real on-disk state: a deleted seed
     * (e.g. guard.png) is never silently recreated, otherwise data-loss regressions hide.
     */
    private synchronized void seedAtStartup() {
        File images = imagesDir();
        File readonly = readonlyDir();
        File funbox = funboxDir();
        images.mkdirs();
        readonly.mkdirs();
        funbox.mkdirs();
        new File(images, SUB_FOLDER).mkdirs();
        new File(images, PROTECTED_FOLDER).mkdirs();
        new File(funbox, FUNBOX_PACK).mkdirs();

        writeBytes(new File(images, PNG_NAME), createPngBytes(8, 6));
        writeBytes(new File(images, GIF_NAME), twoFrameGifBytes());
        writeBytes(new File(new File(images, SUB_FOLDER), SUB_IMAGE_NAME), createPngBytes(5, 5));
        writeBytes(new File(readonly, READONLY_PNG_NAME), createPngBytes(4, 4));
        writeBytes(new File(new File(images, PROTECTED_FOLDER), PROTECTED_IMAGE_NAME), createPngBytes(3, 4));
        writeBytes(new File(new File(funbox, FUNBOX_PACK), FUNBOX_IMAGE_NAME), createPngBytes(4, 6));
    }

    /** Provider methods run after onCreate; never re-seed here or real deletions get masked. */
    private void checkInitialized() {
        if (!initialized) {
            throw new IllegalStateException("SafFixtureProvider used before startup seeding");
        }
    }

    private static void writeBytes(File file, byte[] bytes) {
        try {
            java.io.FileOutputStream stream = new java.io.FileOutputStream(file);
            try {
                stream.write(bytes);
            } finally {
                stream.close();
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot seed " + file.getAbsolutePath(), error);
        }
    }

    private static byte[] twoFrameGifBytes() {
        String hex = TWO_FRAME_GIF_HEX;
        if ((hex.length() & 1) != 0) {
            throw new IllegalStateException("GIF hex must be even");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            int high = Character.digit(hex.charAt(index * 2), 16);
            int low = Character.digit(hex.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalStateException("Invalid GIF hex");
            }
            bytes[index] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    private static byte[] createPngBytes(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    bitmap.setPixel(x, y, Color.rgb(32 + x * 20, 64 + y * 20, 160));
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("Cannot encode fixture PNG");
            }
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private String documentIdOf(File file) throws FileNotFoundException {
        File images = imagesDir();
        File readonly = readonlyDir();
        File funbox = funboxDir();
        if (file.equals(images)) {
            return ROOT_IMAGES;
        }
        if (file.equals(readonly)) {
            return ROOT_READONLY;
        }
        if (file.equals(funbox)) {
            return ROOT_FUNBOX;
        }
        String path = file.getAbsolutePath();
        String imageBase = images.getAbsolutePath() + File.separator;
        if (path.startsWith(imageBase)) {
            return ROOT_IMAGES + "/" + path.substring(imageBase.length());
        }
        String readOnlyBase = readonly.getAbsolutePath() + File.separator;
        if (path.startsWith(readOnlyBase)) {
            return ROOT_READONLY + "/" + path.substring(readOnlyBase.length());
        }
        String funboxBase = funbox.getAbsolutePath() + File.separator;
        if (path.startsWith(funboxBase)) {
            return ROOT_FUNBOX + "/" + path.substring(funboxBase.length());
        }
        throw new FileNotFoundException("Unknown document: " + path);
    }

    private File resolveFile(String documentId) throws FileNotFoundException {
        String normalized = trimSlashes(documentId);
        int slash = normalized.indexOf('/');
        String rootId = slash < 0 ? normalized : normalized.substring(0, slash);
        String relative = slash < 0 ? "" : normalized.substring(slash + 1);
        File base;
        if (ROOT_IMAGES.equals(rootId)) {
            base = imagesDir();
        } else if (ROOT_READONLY.equals(rootId)) {
            base = readonlyDir();
        } else if (ROOT_FUNBOX.equals(rootId)) {
            base = funboxDir();
        } else {
            throw new FileNotFoundException("Unknown root: " + documentId);
        }
        if (relative.isEmpty()) {
            return base;
        }
        File candidate = new File(base, relative);
        try {
            String basePath = base.getCanonicalPath() + File.separator;
            if (!candidate.getCanonicalPath().startsWith(basePath)) {
                throw new FileNotFoundException("Escaping document id: " + documentId);
            }
        } catch (IOException error) {
            throw new FileNotFoundException("Invalid document id: " + documentId);
        }
        if (!candidate.exists()) {
            throw new FileNotFoundException("Missing document: " + documentId);
        }
        return candidate;
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isWritableRoot(String documentId) {
        return ROOT_IMAGES.equals(documentId)
                || documentId.startsWith(ROOT_IMAGES + "/")
                || ROOT_FUNBOX.equals(documentId)
                || documentId.startsWith(ROOT_FUNBOX + "/");
    }

    private static String mimeOf(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        }
        String name = file.getName().toLowerCase(java.util.Locale.US);
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    private static int documentFlags(String documentId, File file) {
        if (!isWritableRoot(documentId)) {
            return 0;
        }
        if (file.isDirectory()) {
            // FLAG_DIR_SUPPORTS_CREATE mirrors a writable tree root; roots themselves are
            // protected by the storage layer and by deleteDocument().
            int flags = Document.FLAG_DIR_SUPPORTS_CREATE;
            // Any directory named Protected advertises create but never its own deletion,
            // including nested occurrences used by the recursive-delete preflight test.
            boolean protectedDirectory = PROTECTED_FOLDER.equals(file.getName());
            boolean isRoot = ROOT_IMAGES.equals(documentId) || ROOT_FUNBOX.equals(documentId);
            if (!isRoot && !protectedDirectory) {
                flags |= Document.FLAG_SUPPORTS_DELETE;
            }
            return flags;
        }
        return Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        checkInitialized();
        String[] columns = projection != null ? projection : DEFAULT_ROOT_COLUMNS;
        MatrixCursor cursor = new MatrixCursor(columns);
        addRoot(cursor, columns, ROOT_IMAGES, ROOT_IMAGES_TITLE, true);
        addRoot(cursor, columns, ROOT_READONLY, ROOT_READONLY_TITLE, false);
        addRoot(cursor, columns, ROOT_FUNBOX, ROOT_FUNBOX_TITLE, true);
        return cursor;
    }

    private void addRoot(MatrixCursor cursor, String[] columns, String rootId, String title, boolean writable) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            Object value;
            if (Root.COLUMN_ROOT_ID.equals(column)) {
                value = rootId;
            } else if (Root.COLUMN_DOCUMENT_ID.equals(column)) {
                value = rootId;
            } else if (Root.COLUMN_TITLE.equals(column)) {
                value = title;
            } else if (Root.COLUMN_SUMMARY.equals(column)) {
                value = writable ? "Writable test tree" : "Read-only test tree";
            } else if (Root.COLUMN_FLAGS.equals(column)) {
                // Both writable roots and the read-only root advertise create so DocumentsUI
                // lets the user select them; the read-only root rejects every mutation and
                // reports no per-document write/delete flags.
                value = Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE;
            } else if (Root.COLUMN_ICON.equals(column)) {
                value = android.R.drawable.ic_menu_gallery;
            } else if (Root.COLUMN_AVAILABLE_BYTES.equals(column)) {
                value = 1024L * 1024L;
            } else if (Root.COLUMN_CAPACITY_BYTES.equals(column)) {
                value = 1024L * 1024L * 64L;
            } else {
                value = null;
            }
            row.add(value);
        }
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        checkInitialized();
        File file = resolveFile(documentId);
        return documentCursor(projection, documentId, file);
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        checkInitialized();
        File parent = resolveFile(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Not a directory: " + parentDocumentId);
        }
        String[] columns = projection != null ? projection : DEFAULT_DOCUMENT_COLUMNS;
        MatrixCursor cursor = new MatrixCursor(columns);
        File[] children = parent.listFiles();
        if (children != null) {
            java.util.Arrays.sort(children, new java.util.Comparator<File>() {
                @Override
                public int compare(File first, File second) {
                    return first.getName().compareToIgnoreCase(second.getName());
                }
            });
            for (File child : children) {
                addDocumentRow(cursor, columns, child, null);
            }
        }
        return cursor;
    }

    @Override
    public Cursor queryRecentDocuments(String rootId, String[] projection) throws FileNotFoundException {
        checkInitialized();
        String[] columns = projection != null ? projection : DEFAULT_DOCUMENT_COLUMNS;
        MatrixCursor cursor = new MatrixCursor(columns);
        File base;
        if (ROOT_IMAGES.equals(rootId)) {
            base = imagesDir();
        } else if (ROOT_READONLY.equals(rootId)) {
            base = readonlyDir();
        } else if (ROOT_FUNBOX.equals(rootId)) {
            base = funboxDir();
        } else {
            return cursor;
        }
        File[] children = base.listFiles();
        if (children != null) {
            java.util.Arrays.sort(children, new java.util.Comparator<File>() {
                @Override
                public int compare(File first, File second) {
                    return Long.compare(second.lastModified(), first.lastModified());
                }
            });
            for (File child : children) {
                if (child.isFile()) {
                    addDocumentRow(cursor, columns, child, null);
                }
            }
        }
        return cursor;
    }

    private Cursor documentCursor(String[] projection, String documentId, File file) throws FileNotFoundException {
        String[] columns = projection != null ? projection : DEFAULT_DOCUMENT_COLUMNS;
        MatrixCursor cursor = new MatrixCursor(columns);
        addDocumentRow(cursor, columns, file, documentId);
        return cursor;
    }

    private void addDocumentRow(MatrixCursor cursor, String[] columns, File file, String explicitDocumentId) throws FileNotFoundException {
        String documentId = explicitDocumentId != null ? explicitDocumentId : documentIdOf(file);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            Object value;
            if (Document.COLUMN_DOCUMENT_ID.equals(column)) {
                value = documentId;
            } else if (Document.COLUMN_DISPLAY_NAME.equals(column)) {
                value = file.getName();
            } else if (Document.COLUMN_MIME_TYPE.equals(column)) {
                value = mimeOf(file);
            } else if (Document.COLUMN_SIZE.equals(column)) {
                value = file.isDirectory() ? 0L : file.length();
            } else if (Document.COLUMN_LAST_MODIFIED.equals(column)) {
                value = file.isDirectory() ? 0L : file.lastModified();
            } else if (Document.COLUMN_FLAGS.equals(column)) {
                value = documentFlags(documentId, file);
            } else if (Document.COLUMN_SUMMARY.equals(column)) {
                value = null;
            } else if (Document.COLUMN_ICON.equals(column)) {
                value = file.isDirectory()
                        ? android.R.drawable.ic_menu_set_as
                        : android.R.drawable.ic_menu_gallery;
            } else {
                value = null;
            }
            row.add(value);
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        checkInitialized();
        File file = resolveFile(documentId);
        if (file.isDirectory()) {
            throw new FileNotFoundException("Cannot open a directory: " + documentId);
        }
        boolean wantsWrite = mode.indexOf('w') >= 0;
        if (wantsWrite && !isWritableRoot(documentId)) {
            throw new SecurityException("Read-only fixture root: " + documentId);
        }
        int flags;
        if ("r".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_READ_ONLY;
        } else if ("w".equals(mode) || "wt".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE;
        } else if ("wa".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_APPEND;
        } else if ("rw".equals(mode) || "rwt".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE;
        } else {
            throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        checkInitialized();
        File file = resolveFile(documentId);
        if (!file.isFile()) {
            throw new FileNotFoundException("Not a file: " + documentId);
        }
        // The fixture images are tiny; serving the original keeps the grid deterministic.
        return new AssetFileDescriptor(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                0,
                AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        checkInitialized();
        if (!isWritableRoot(parentDocumentId)) {
            throw new SecurityException("Read-only fixture root: " + parentDocumentId);
        }
        File parent = resolveFile(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Not a directory: " + parentDocumentId);
        }
        String safeName = displayName.trim();
        if (safeName.isEmpty()
                || safeName.contains("/")
                || safeName.contains("\\")
                || ".".equals(safeName)
                || "..".equals(safeName)) {
            throw new IllegalArgumentException("Invalid display name: " + displayName);
        }
        File target = new File(parent, safeName);
        if (target.exists()) {
            throw new FileNotFoundException("Document already exists: " + safeName);
        }
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            if (!target.mkdir()) {
                throw new FileNotFoundException("Cannot create directory: " + safeName);
            }
        } else {
            try {
                if (!target.createNewFile()) {
                    throw new FileNotFoundException("Cannot create file: " + safeName);
                }
            } catch (IOException error) {
                throw new FileNotFoundException("Cannot create file: " + safeName);
            }
        }
        return documentIdOf(target);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        checkInitialized();
        if (ROOT_IMAGES.equals(documentId)
                || ROOT_READONLY.equals(documentId)
                || ROOT_FUNBOX.equals(documentId)) {
            throw new SecurityException("Fixture roots cannot be deleted");
        }
        if (isProtectedDirectoryId(documentId)) {
            // Directory allows creating children but never deleting itself; reject before
            // looking at (or touching) its contents. Applies at any nesting level.
            throw new SecurityException("Protected fixture directory cannot be deleted: " + documentId);
        }
        if (!isWritableRoot(documentId)) {
            throw new SecurityException("Read-only fixture root: " + documentId);
        }
        File file = resolveFile(documentId);
        String[] contents = file.list();
        if (file.isDirectory() && contents != null && contents.length > 0) {
            throw new FileNotFoundException("Directory is not empty: " + documentId);
        }
        if (!file.delete()) {
            throw new FileNotFoundException("Cannot delete: " + documentId);
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        checkInitialized();
        if (!isWritableRoot(documentId)
                || ROOT_IMAGES.equals(documentId)
                || ROOT_FUNBOX.equals(documentId)
                || isProtectedDirectoryId(documentId)) {
            throw new SecurityException("Cannot rename: " + documentId);
        }
        String safeName = displayName.trim();
        if (safeName.isEmpty() || safeName.contains("/") || safeName.contains("\\")) {
            throw new IllegalArgumentException("Invalid display name: " + displayName);
        }
        File file = resolveFile(documentId);
        File parent = file.getParentFile();
        if (parent == null) {
            throw new FileNotFoundException("No parent: " + documentId);
        }
        File target = new File(parent, safeName);
        if (target.exists()) {
            throw new FileNotFoundException("Document already exists: " + safeName);
        }
        if (!file.renameTo(target)) {
            throw new FileNotFoundException("Cannot rename: " + documentId);
        }
        return documentIdOf(target);
    }

    private static boolean isProtectedDirectoryId(String documentId) {
        int slash = documentId.lastIndexOf('/');
        String name = slash < 0 ? documentId : documentId.substring(slash + 1);
        return PROTECTED_FOLDER.equals(name);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        // Strict descendant only; Android's enforceTree handles the self case explicitly.
        if (parentDocumentId.equals(documentId)) {
            return false;
        }
        return documentId.startsWith(parentDocumentId + "/");
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        checkInitialized();
        return mimeOf(resolveFile(documentId));
    }
}
