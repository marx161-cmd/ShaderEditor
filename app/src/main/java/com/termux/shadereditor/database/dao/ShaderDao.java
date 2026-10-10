package com.termux.shadereditor.database.dao;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.Contract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;

import com.termux.shadereditor.R;
import com.termux.shadereditor.database.DataRecords;
import com.termux.shadereditor.database.DatabaseContract;
import com.termux.shadereditor.database.DatabaseTable;
import com.termux.shadereditor.resource.Resources;

/**
 * Shaders are plain .glsl files in Termux's home ({@link #SHADER_DIR}), one
 * file per shader, filename = shader name, so they can be edited from a shell.
 * Numeric ids are kept for the rest of the app: an id -> filename index,
 * per-shader quality and thumbnails live in the app's own files dir.
 * The old "shaders" table is only read once, to migrate it into files.
 */
public class ShaderDao {
	public static final File SHADER_DIR =
			new File("/data/data/com.termux/files/home/shaders");

	private static final String TAG = "ShaderDao";
	private static final String EXTENSION = ".glsl";
	private static final String TRASH_DIR = ".trash";
	private static final String KEY_FILE = ".file";
	private static final String KEY_QUALITY = ".quality";

	@Nullable
	private static volatile ShaderDao instance;

	@NonNull
	private final SQLiteOpenHelper dbHelper;
	@NonNull
	private final Context appContext;
	@NonNull
	private final File metaDir;
	@NonNull
	private final File indexFile;
	@NonNull
	private final File thumbDir;
	@NonNull
	private final Properties index = new Properties();
	private boolean loaded;

	public ShaderDao(@NonNull SQLiteOpenHelper dbHelper, @NonNull Context context) {
		this.dbHelper = dbHelper;
		this.appContext = context;
		metaDir = new File(context.getFilesDir(), "shader_meta");
		indexFile = new File(metaDir, "index.properties");
		thumbDir = new File(metaDir, "thumbs");
		instance = this;
	}

	// region Public API
	@Nullable
	public synchronized DataRecords.Shader getShader(long id) {
		sync();
		File file = fileOf(id);
		if (file == null) {
			return null;
		}
		String source = readText(file);
		if (source == null) {
			return null;
		}
		return new DataRecords.Shader(
				id,
				source,
				nameOf(file),
				formatTime(file.lastModified()),
				qualityOf(id));
	}

	public synchronized List<DataRecords.ShaderInfo> getShaders(
			boolean sortByLastModification) {
		sync();
		List<DataRecords.ShaderInfo> shaders = new ArrayList<>();
		for (long id : ids()) {
			File file = fileOf(id);
			if (file == null) {
				continue;
			}
			shaders.add(new DataRecords.ShaderInfo(
					id,
					nameOf(file),
					formatTime(file.lastModified()),
					readBytes(thumbFile(id))));
		}
		if (sortByLastModification) {
			// "yyyy-MM-dd HH:mm:ss" sorts lexicographically.
			shaders.sort(Comparator.comparing(
					(DataRecords.ShaderInfo s) -> s.modified() == null ? "" : s.modified())
					.reversed());
		}
		return shaders;
	}

	@Nullable
	public synchronized DataRecords.Shader getRandomShader() {
		sync();
		List<Long> ids = ids();
		if (ids.isEmpty()) {
			return null;
		}
		return getShader(ids.get(new Random().nextInt(ids.size())));
	}

	@Nullable
	public synchronized byte[] getThumbnail(long id) {
		return readBytes(thumbFile(id));
	}

	public synchronized long getFirstShaderId() {
		sync();
		List<Long> ids = ids();
		return ids.isEmpty() ? 0 : ids.get(0);
	}

	public long insertShaderFromResource(@NonNull Context context, @Nullable String name,
			int sourceId, int thumbId,
			float quality) {
		try {
			return insertShader(
					Resources.loadRawResource(context, sourceId),
					name,
					Resources.loadBitmapResource(context, thumbId),
					quality);
		} catch (IOException e) {
			return 0;
		}
	}

	public long insertNewShader() {
		return insertShaderFromResource(
				appContext,
				null,
				R.raw.new_shader,
				R.drawable.thumbnail_new_shader,
				1f);
	}

	public synchronized long insertShader(@NonNull String shader, @Nullable String name,
			@Nullable byte[] thumbnail, float quality) {
		sync();
		long id = nextId();
		File file = uniqueFile(name, "shader_" + id);
		if (!writeText(file, shader)) {
			return 0;
		}
		index.setProperty(id + KEY_FILE, file.getName());
		index.setProperty(id + KEY_QUALITY, String.valueOf(quality));
		if (thumbnail != null) {
			writeBytes(thumbFile(id), thumbnail);
		}
		saveIndex();
		return id;
	}

	public synchronized void updateShader(long id, @Nullable String shader,
			@Nullable byte[] thumbnail, float quality) {
		sync();
		File file = fileOf(id);
		if (file == null) {
			return;
		}
		if (shader != null) {
			writeText(file, shader);
		} else {
			//noinspection ResultOfMethodCallIgnored
			file.setLastModified(System.currentTimeMillis());
		}
		if (thumbnail != null) {
			writeBytes(thumbFile(id), thumbnail);
		}
		index.setProperty(id + KEY_QUALITY, String.valueOf(quality));
		saveIndex();
	}

	public synchronized void updateShaderName(long id, @NonNull String name) {
		sync();
		File file = fileOf(id);
		if (file == null || sanitizeName(name) == null ||
				nameOf(file).equals(sanitizeName(name))) {
			return;
		}
		File target = uniqueFile(name, nameOf(file));
		if (file.renameTo(target)) {
			index.setProperty(id + KEY_FILE, target.getName());
			saveIndex();
		} else {
			Log.e(TAG, "rename failed: " + file + " -> " + target);
		}
	}

	public synchronized void removeShader(long id) {
		sync();
		File file = fileOf(id);
		if (file != null) {
			File trash = new File(SHADER_DIR, TRASH_DIR);
			//noinspection ResultOfMethodCallIgnored
			trash.mkdirs();
			File target = new File(trash, file.getName());
			if (target.exists()) {
				target = new File(trash, nameOf(file) + "_" +
						System.currentTimeMillis() + EXTENSION);
			}
			if (!file.renameTo(target)) {
				Log.e(TAG, "move to trash failed: " + file);
				return;
			}
		}
		forget(id);
		saveIndex();
	}
	// endregion

	// region Index
	// Loads the index (migrating the old table on first run), then
	// reconciles it with the folder: new .glsl files get ids, entries
	// whose file is gone are dropped.
	private void sync() {
		if (!loaded) {
			loaded = true;
			if (indexFile.exists()) {
				try (InputStream in = new FileInputStream(indexFile)) {
					index.load(in);
				} catch (IOException e) {
					Log.e(TAG, "cannot read " + indexFile, e);
				}
			} else {
				migrateFromDatabase();
			}
		}
		if (!SHADER_DIR.isDirectory() && !SHADER_DIR.mkdirs()) {
			Log.e(TAG, "cannot create " + SHADER_DIR);
			return;
		}
		Map<String, Long> known = new TreeMap<>();
		for (long id : ids()) {
			known.put(index.getProperty(id + KEY_FILE), id);
		}
		boolean changed = false;
		for (Map.Entry<String, Long> entry : known.entrySet()) {
			if (!new File(SHADER_DIR, entry.getKey()).isFile()) {
				forget(entry.getValue());
				changed = true;
			}
		}
		File[] files = SHADER_DIR.listFiles();
		if (files != null) {
			for (File file : files) {
				String fileName = file.getName();
				if (file.isFile() && !fileName.startsWith(".") &&
						fileName.endsWith(EXTENSION) &&
						!known.containsKey(fileName)) {
					index.setProperty(nextId() + KEY_FILE, fileName);
					changed = true;
				}
			}
		}
		if (changed) {
			saveIndex();
		}
	}

	private void migrateFromDatabase() {
		if (!SHADER_DIR.isDirectory() && !SHADER_DIR.mkdirs()) {
			Log.e(TAG, "cannot create " + SHADER_DIR + ", not migrating");
			loaded = false;
			return;
		}
		SimpleDateFormat format = timeFormat();
		int count = 0;
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (Cursor cursor = db.rawQuery("SELECT * FROM " +
				DatabaseContract.ShaderColumns.TABLE_NAME + " ORDER BY " +
				DatabaseContract.ShaderColumns._ID, null)) {
			while (cursor.moveToNext()) {
				long id = CursorHelpers.getLong(cursor,
						DatabaseContract.ShaderColumns._ID);
				String source = CursorHelpers.getString(cursor,
						DatabaseContract.ShaderColumns.FRAGMENT_SHADER);
				File file = uniqueFile(
						CursorHelpers.getString(cursor,
								DatabaseContract.ShaderColumns.NAME),
						"shader_" + id);
				if (!writeText(file, source == null ? "" : source)) {
					// Leave the index unwritten so the next start retries.
					loaded = false;
					index.clear();
					return;
				}
				String modified = CursorHelpers.getString(cursor,
						DatabaseContract.ShaderColumns.MODIFIED);
				if (modified != null) {
					try {
						Date date = format.parse(modified);
						if (date != null) {
							//noinspection ResultOfMethodCallIgnored
							file.setLastModified(date.getTime());
						}
					} catch (ParseException ignored) {
						// Keep the current time.
					}
				}
				index.setProperty(id + KEY_FILE, file.getName());
				index.setProperty(id + KEY_QUALITY, String.valueOf(
						CursorHelpers.getFloat(cursor,
								DatabaseContract.ShaderColumns.QUALITY)));
				byte[] thumb = CursorHelpers.getBlob(cursor,
						DatabaseContract.ShaderColumns.THUMB);
				if (thumb != null) {
					writeBytes(thumbFile(id), thumb);
				}
				++count;
			}
		}
		saveIndex();
		Log.i(TAG, "migrated " + count + " shaders to " + SHADER_DIR);
	}

	@NonNull
	private List<Long> ids() {
		List<Long> ids = new ArrayList<>();
		for (String key : index.stringPropertyNames()) {
			if (key.endsWith(KEY_FILE)) {
				try {
					ids.add(Long.parseLong(
							key.substring(0, key.length() - KEY_FILE.length())));
				} catch (NumberFormatException ignored) {
					// Not one of ours.
				}
			}
		}
		ids.sort(null);
		return ids;
	}

	private long nextId() {
		List<Long> ids = ids();
		return ids.isEmpty() ? 1 : ids.get(ids.size() - 1) + 1;
	}

	@Nullable
	private File fileOf(long id) {
		String fileName = index.getProperty(id + KEY_FILE);
		if (fileName == null) {
			return null;
		}
		File file = new File(SHADER_DIR, fileName);
		return file.isFile() ? file : null;
	}

	private float qualityOf(long id) {
		try {
			float quality = Float.parseFloat(
					index.getProperty(id + KEY_QUALITY, "1"));
			return quality > 0f ? quality : 1f;
		} catch (NumberFormatException e) {
			return 1f;
		}
	}

	private void forget(long id) {
		index.remove(id + KEY_FILE);
		index.remove(id + KEY_QUALITY);
		//noinspection ResultOfMethodCallIgnored
		thumbFile(id).delete();
	}

	private void saveIndex() {
		//noinspection ResultOfMethodCallIgnored
		metaDir.mkdirs();
		File tmp = new File(metaDir, indexFile.getName() + ".tmp");
		try (OutputStream out = new FileOutputStream(tmp)) {
			index.store(out, "shader id -> file in " + SHADER_DIR);
		} catch (IOException e) {
			Log.e(TAG, "cannot write " + tmp, e);
			return;
		}
		if (!tmp.renameTo(indexFile)) {
			Log.e(TAG, "cannot replace " + indexFile);
		}
	}

	@NonNull
	private File thumbFile(long id) {
		return new File(thumbDir, id + ".png");
	}
	// endregion

	// region Files
	@NonNull
	private static String nameOf(@NonNull File file) {
		String name = file.getName();
		return name.endsWith(EXTENSION)
				? name.substring(0, name.length() - EXTENSION.length())
				: name;
	}

	@Nullable
	private static String sanitizeName(@Nullable String name) {
		if (name == null) {
			return null;
		}
		String clean = name.trim();
		if (clean.endsWith(EXTENSION)) {
			clean = clean.substring(0, clean.length() - EXTENSION.length());
		}
		clean = clean.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
				.replaceAll("^\\.+", "")
				.trim();
		if (clean.length() > 100) {
			clean = clean.substring(0, 100);
		}
		return clean.isEmpty() ? null : clean;
	}

	// Never returns an existing file, so nothing is ever overwritten.
	@NonNull
	private static File uniqueFile(@Nullable String name, @NonNull String fallback) {
		String base = sanitizeName(name);
		if (base == null) {
			base = fallback;
		}
		File file = new File(SHADER_DIR, base + EXTENSION);
		for (int n = 2; file.exists(); ++n) {
			file = new File(SHADER_DIR, base + "_" + n + EXTENSION);
		}
		return file;
	}

	@Nullable
	private static String readText(@NonNull File file) {
		byte[] bytes = readBytes(file);
		return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
	}

	private static boolean writeText(@NonNull File file, @NonNull String text) {
		// Write next to the target and rename, so a reader never sees half a file.
		File tmp = new File(file.getParentFile(), "." + file.getName() + ".tmp");
		if (!writeBytes(tmp, text.getBytes(StandardCharsets.UTF_8))) {
			return false;
		}
		if (!tmp.renameTo(file)) {
			Log.e(TAG, "cannot replace " + file);
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			return false;
		}
		return true;
	}

	@Nullable
	private static byte[] readBytes(@NonNull File file) {
		if (!file.isFile()) {
			return null;
		}
		try (InputStream in = new FileInputStream(file)) {
			byte[] bytes = new byte[(int) file.length()];
			int off = 0;
			while (off < bytes.length) {
				int n = in.read(bytes, off, bytes.length - off);
				if (n < 0) {
					break;
				}
				off += n;
			}
			return bytes;
		} catch (IOException e) {
			Log.e(TAG, "cannot read " + file, e);
			return null;
		}
	}

	private static boolean writeBytes(@NonNull File file, @NonNull byte[] bytes) {
		File dir = file.getParentFile();
		if (dir != null) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		try (OutputStream out = new FileOutputStream(file)) {
			out.write(bytes);
			return true;
		} catch (IOException e) {
			Log.e(TAG, "cannot write " + file, e);
			return false;
		}
	}

	@NonNull
	private static String formatTime(long millis) {
		return timeFormat().format(new Date(millis));
	}

	@NonNull
	private static SimpleDateFormat timeFormat() {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
	}
	// endregion

	// region Legacy table (kept so old databases open and migrate)
	private static long insertShaderRow(@NonNull SQLiteDatabase db, @NonNull String shader,
			@Nullable String name,
			@Nullable byte[] thumbnail,
			float quality) {
		String now = formatTime(System.currentTimeMillis());
		var cv = new android.content.ContentValues();
		cv.put(DatabaseContract.ShaderColumns.FRAGMENT_SHADER, shader);
		cv.put(DatabaseContract.ShaderColumns.THUMB, thumbnail);
		cv.put(DatabaseContract.ShaderColumns.NAME, name);
		cv.put(DatabaseContract.ShaderColumns.CREATED, now);
		cv.put(DatabaseContract.ShaderColumns.MODIFIED, now);
		cv.put(DatabaseContract.ShaderColumns.QUALITY, quality);
		return db.insert(DatabaseContract.ShaderColumns.TABLE_NAME, null, cv);
	}

	private static void addShaderNames(@NonNull SQLiteDatabase db) {
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.NAME + " TEXT;");
	}

	private static void addShadersQuality(@NonNull SQLiteDatabase db) {
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.QUALITY + " REAL;");
		db.execSQL("UPDATE " + DatabaseContract.ShaderColumns.TABLE_NAME + " SET " + DatabaseContract.ShaderColumns.QUALITY + " = " +
				"1;");
	}

	@NonNull
	@Contract("_ -> new")
	public static DatabaseTable buildSchema(@NonNull Context context) {
		return new DatabaseTable() {
			@Override
			public void onCreate(@NonNull SQLiteDatabase db) {
				createShadersTable(db);
			}

			@Override
			public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
				if (oldVersion < 3) {
					addShadersQuality(db);
				}
				if (oldVersion < 5) {
					addShaderNames(db);
				}
			}

			@Override
			public boolean onImport(@NonNull SQLiteDatabase intoDb,
					@NonNull SQLiteDatabase fromDb) {
				// Imported shaders become files too.
				ShaderDao dao = instance;
				return dao != null && dao.importShaders(fromDb);
			}

			private void createShadersTable(@NonNull SQLiteDatabase db) {
				db.execSQL("CREATE TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " (" +
						DatabaseContract.ShaderColumns._ID + " INTEGER PRIMARY KEY " +
						"AUTOINCREMENT," +
						DatabaseContract.ShaderColumns.FRAGMENT_SHADER + " TEXT NOT NULL," +
						DatabaseContract.ShaderColumns.THUMB + " BLOB," +
						DatabaseContract.ShaderColumns.NAME + " TEXT," +
						DatabaseContract.ShaderColumns.CREATED + " DATETIME," +
						DatabaseContract.ShaderColumns.MODIFIED + " DATETIME," +
						DatabaseContract.ShaderColumns.QUALITY + " REAL);");
				// A fresh install gets the default shader as a row; the
				// first migration turns it into a file.
				try {
					insertShaderRow(db,
							Resources.loadRawResource(context, R.raw.default_shader),
							context.getString(R.string.default_shader),
							Resources.loadBitmapResource(context,
									R.drawable.thumbnail_default),
							1f);
				} catch (IOException e) {
					// This should not happen with packaged resources.
					Toast.makeText(context, "Failed to load initial shaders", Toast.LENGTH_SHORT).show();
				}
			}
		};
	}

	private boolean importShaders(@NonNull SQLiteDatabase src) {
		try (Cursor cursor = src.rawQuery(
				"SELECT * FROM " + DatabaseContract.ShaderColumns.TABLE_NAME +
						" ORDER BY " + DatabaseContract.ShaderColumns._ID,
				null)) {
			if (cursor == null) {
				return false;
			}
			while (cursor.moveToNext()) {
				String source = CursorHelpers.getString(cursor,
						DatabaseContract.ShaderColumns.FRAGMENT_SHADER);
				if (source == null) {
					continue;
				}
				float quality = CursorHelpers.getFloat(cursor,
						DatabaseContract.ShaderColumns.QUALITY);
				if (insertShader(source,
						CursorHelpers.getString(cursor,
								DatabaseContract.ShaderColumns.NAME),
						CursorHelpers.getBlob(cursor,
								DatabaseContract.ShaderColumns.THUMB),
						quality > 0f ? quality : 1f) < 1) {
					return false;
				}
			}
			return true;
		}
	}
	// endregion
}
