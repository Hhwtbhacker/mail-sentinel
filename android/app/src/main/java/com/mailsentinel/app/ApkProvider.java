package com.mailsentinel.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

public class ApkProvider extends ContentProvider {
    static final String AUTHORITY = "com.mailsentinel.app.apk";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/update.apk");

    static File updateFile(android.content.Context context) {
        return new File(new File(context.getFilesDir(), "updates"), "update.apk");
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return "/update.apk".equals(uri.getPath()) ? "application/vnd.android.package-archive" : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/update.apk".equals(uri.getPath()) || !"r".equals(mode)) throw new FileNotFoundException();
        File file = updateFile(getContext());
        if (!file.isFile()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        if (!"/update.apk".equals(uri.getPath())) return null;
        String[] columns = projection == null ? new String[]{"_display_name", "_size"} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if ("_display_name".equals(columns[i])) values[i] = "邮箱总管.apk";
            if ("_size".equals(columns[i])) values[i] = updateFile(getContext()).length();
        }
        cursor.addRow(values);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
