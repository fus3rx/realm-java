/*
 * Copyright 2014 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm;

import android.annotation.TargetApi;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.JsonReader;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.realm.exceptions.RealmException;
import io.realm.exceptions.RealmIOException;
import io.realm.exceptions.RealmMigrationNeededException;
import io.realm.internal.ColumnType;
import io.realm.internal.ImplicitTransaction;
import io.realm.internal.Row;
import io.realm.internal.SharedGroup;
import io.realm.internal.Table;
import io.realm.internal.TableView;


/**
 * <p>The Realm class is the storage and transactional manager of your object persistent store. It is in charge of
 * creating instances of your RealmObjects.
 * Objects within a Realm can be queried and read at any time. Creating,
 * modifying, and deleting objects must be done through transactions.</p>
 *
 * <p>The transactions ensure that multiple instances (on multiple threads) can access the objects
 * in a consistent state with full ACID guaranties.</p>
 *
 * <p>If auto-refresh is set the instance of the Realm will be automatically updated when another instance commits a
 * change (create, modify or delete an object). This feature requires the Realm instance to be residing in a
 * thread attached to a Looper (the main thread has a Looper by default)</p>
 *
 *<p>For normal threads Android provides a utility class that can be used like this:</p>
 *
 * <pre>
 * HandlerThread thread = new HandlerThread("MyThread") {
 *    \@Override
 *    protected void onLooperPrepared() {
 *       Realm realm = Realm.getInstance(getContext());
 *       // This realm will be updated by the event loop
 *       // on every commit performed by other realm instances
 *    }
 * };
 * thread.start();
 * </pre>
 *
 * It is important to remember to call the close() method when done with the Realm instance.
 */
public final class Realm implements Closeable {
    public static final String DEFAULT_REALM_NAME = "default.realm";

    private static final String TAG = "REALM";
    private static final String TABLE_PREFIX = "class_";
    protected static final ThreadLocal<Map<Integer, Realm>> realmsCache = new ThreadLocal<Map<Integer, Realm>>() {
        @SuppressLint("UseSparseArrays")
        @Override
        protected Map<Integer, Realm> initialValue() {
            return new HashMap<Integer, Realm>(); // On Android we could use SparseArray<Realm> which is faster,
                                                  // but incompatible with Java
        }
    };
    private static final ThreadLocal<Map<Integer, Integer>> referenceCount
            = new ThreadLocal<Map<Integer,Integer>>() {
        @SuppressLint("UseSparseArrays")
        @Override
        protected Map<Integer, Integer> initialValue() {
            return new HashMap<Integer, Integer>();
        }
    };
    private static final int REALM_CHANGED = 14930352; // Just a nice big Fibonacci number. For no reason :)
    protected static final Map<Handler, Integer> handlers = new ConcurrentHashMap<Handler, Integer>();
    private static final String APT_NOT_EXECUTED_MESSAGE = "Annotation processor may not have been executed.";
    private static final String INCORRECT_THREAD_MESSAGE = "Realm access from incorrect thread. Realm objects can only be accessed on the thread they where created.";
    private static final String CLOSED_REALM = "This Realm instance has already been closed, making it unusable.";

    @SuppressWarnings("UnusedDeclaration")
    private static SharedGroup.Durability defaultDurability = SharedGroup.Durability.FULL;
    private boolean autoRefresh;
    private Handler handler;

    private final int id;
    private final String path;
    private SharedGroup sharedGroup;
    private final ImplicitTransaction transaction;
    private final Map<Class<?>, String> simpleClassNames = new HashMap<Class<?>, String>(); // Map between original class and their class name
    private final Map<String, Class<?>> generatedClasses = new HashMap<String, Class<?>>(); // Map between generated class names and their implementation
    private final Map<Class<?>, Constructor> constructors = new HashMap<Class<?>, Constructor>();
    private final Map<Class<?>, Method> initTableMethods = new HashMap<Class<?>, Method>();
    private final Map<Class<?>, Constructor> generatedConstructors = new HashMap<Class<?>, Constructor>();
    private final List<RealmChangeListener> changeListeners = new ArrayList<RealmChangeListener>();
    private final Map<Class<?>, Table> tables = new HashMap<Class<?>, Table>();
    private static final long UNVERSIONED = -1;

    // Package protected to be reachable by proxy classes
    static final Map<String, Map<String, Long>> columnIndices = new HashMap<String, Map<String, Long>>();

    protected void checkIfValid() {
        // Check if the Realm instance has been closed
        if (sharedGroup == null) {
            throw new IllegalStateException(CLOSED_REALM);
        }

        // Check if we are in the right thread
        Realm currentRealm = realmsCache.get().get(this.id);
        if (currentRealm != this) {
            throw new IllegalStateException(INCORRECT_THREAD_MESSAGE);
        }
    }

    // The constructor in private to enforce the use of the static one
    private Realm(String absolutePath, byte[] key, boolean autoRefresh) {
        this.sharedGroup = new SharedGroup(absolutePath, true, key);
        this.transaction = sharedGroup.beginImplicitTransaction();
        this.path = absolutePath;
        this.id = absolutePath.hashCode();
        setAutoRefresh(autoRefresh);
    }

    @Override
    protected void finalize() throws Throwable {
        this.close();
        super.finalize();
    }

    /**
     * Closes the Realm instance and all its resources.
     * 
     * It's important to always remember to close Realm instances when you're done with it in order 
     * not to leak memory, file descriptors or grow the size of Realm file out of measure.
     */
    @Override
    public void close() {
        Map<Integer, Integer> localRefCount = referenceCount.get();
        Integer references = localRefCount.get(id);
        if (references == null) {
            references = 0;
        }
        if (sharedGroup != null && references == 1) {
            realmsCache.get().remove(id);
            sharedGroup.close();
            sharedGroup = null;
        }
        localRefCount.put(id, references - 1);
        referenceCount.set(localRefCount);

        if (handler != null) {
            handlers.remove(handler);
        }
    }

    private class RealmCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message message) {
            if (message.what == REALM_CHANGED) {
                transaction.advanceRead();
                sendNotifications();
            }
            return true;
        }
    }

    /**
     * Retrieve the auto-refresh status of the Realm instance.
     * @return the auto-refresh status
     */
    public boolean isAutoRefresh() {
        return autoRefresh;
    }

    /**
     * Set the auto-refresh status of the Realm instance.
     *
     * Auto-refresh is a feature that enables automatic update of the current realm instance and all its derived objects
     * (RealmResults and RealmObjects instances) when a commit is performed on a Realm acting on the same file in another thread.
     * This feature is only available if the realm instance lives is a {@link android.os.Looper} enabled thread.
     *
     * @param autoRefresh true will turn auto-refresh on, false will turn it off.
     * @throws java.lang.IllegalStateException if trying to enable auto-refresh in a thread without Looper.
     */
    public void setAutoRefresh(boolean autoRefresh) {
        if (autoRefresh && Looper.myLooper() == null) {
            throw new IllegalStateException("Cannot set auto-refresh in a Thread without a Looper");
        }

        if (autoRefresh && !this.autoRefresh) { // Switch it on
            handler = new Handler(new RealmCallback());
            handlers.put(handler, id);
        } else if (!autoRefresh && this.autoRefresh && handler != null) { // Switch it off
            handler.removeCallbacksAndMessages(null);
            handlers.remove(handler);
        }
        this.autoRefresh = autoRefresh;
    }

//    public static void setDefaultDurability(SharedGroup.Durability durability) {
//        defaultDurability = durability;
//    }

    // Public because of migrations
    public Table getTable(Class<?> clazz) {
        String simpleClassName = simpleClassNames.get(clazz);
        if (simpleClassName == null) {
            simpleClassName = clazz.getSimpleName();
            simpleClassNames.put(clazz, simpleClassName);
        }
        return transaction.getTable(TABLE_PREFIX + simpleClassName);
    }

    /**
     * Realm static constructor for the default realm "default.realm".
     * {link io.realm.close} must be called when you are done using the Realm instance.
     *
     * It sets auto-refresh on if the current thread has a Looper, off otherwise.
     *
     * @param context an Android {@link android.content.Context}
     * @return an instance of the Realm class
     * @throws RealmMigrationNeededException The model classes have been changed and the Realm
     *                                       must be migrated
     * @throws RealmIOException              Error when accessing underlying file
     * @throws java.lang.IllegalStateException The Realm is being instantiated in a Thread without
     *                                         a {@link android.os.Looper}
     * @throws RealmException                Other errors
     */
    public static Realm getInstance(Context context) {
        if (Looper.myLooper() != null) {
            return Realm.getInstance(context, DEFAULT_REALM_NAME, null, true);
        } else {
            return Realm.getInstance(context, DEFAULT_REALM_NAME, null, false);
        }
    }

    /**
     * Realm static constructor for the default realm "default.realm".
     * {link io.realm.close} must be called when you are done using the Realm instance.
     *
     * <strong>This constructor is now deprecated and will be removed in version 0.76.0.</strong>
     *
     * @param context an Android context
     * @param autoRefresh whether the Realm object and its derived objects (RealmResults and RealmObjects)
     *                    should be automatically refreshed with the event loop (requires to be in a thread with a Looper)
     * @return an instance of the Realm class
     * @throws RealmMigrationNeededException The model classes have been changed and the Realm
     *                                       must be migrated
     * @throws RealmIOException              Error when accessing underlying file
     * @throws java.lang.IllegalStateException The Realm is being instantiated with auto-refresh
     *                                         in a Thread without a {@link android.os.Looper}
     * @throws RealmException                Other errors
     */
    @SuppressWarnings("UnusedDeclaration")
    @Deprecated
    public static Realm getInstance(Context context, boolean autoRefresh) {
        return Realm.getInstance(context, DEFAULT_REALM_NAME, null, autoRefresh);
    }

    /**
     * Realm static constructor.
     * {link io.realm.close} must be called when you are done using the Realm instance.
     *
     * It sets auto-refresh on if the current thread has a Looper, off otherwise.
     *
     * @param context  an Android {@link android.content.Context}
     * @param fileName the name of the file to save the Realm to
     * @return an instance of the Realm class
     * @throws RealmMigrationNeededException The model classes have been changed and the Realm
     *                                       must be migrated
     * @throws RealmIOException              Error when accessing underlying file
     * @throws java.lang.IllegalStateException The Realm is being instantiated in a Thread without
     *                                         a {@link android.os.Looper}
     * @throws RealmException                Other errors
     */
    @SuppressWarnings("UnusedDeclaration")
    public static Realm getInstance(Context context, String fileName) {
        if (Looper.myLooper() != null) {
            return Realm.create(context.getFilesDir(), fileName, null, true);
        } else {
            return Realm.create(context.getFilesDir(), fileName, null, false);
        }
    }

    /**
     * Realm static constructor.
     * {link io.realm.close} must be called when you are done using the Realm instance.
     *
     * <strong>This constructor is now deprecated and will be removed in version 0.76.0.</strong>
     *
     * @param context  an Android context
     * @param fileName the name of the file to save the Realm to
     * @param autoRefresh whether the Realm object and its derived objects (RealmResults and RealmObjects)
     *                    should be automatically refreshed with the event loop (requires to be in a thread with a Looper)
     * @return an instance of the Realm class
     * @throws RealmMigrationNeededException The model classes have been changed and the Realm
     *                                       must be migrated
     * @throws RealmIOException              Error when accessing underlying file
     * @throws java.lang.IllegalStateException The Realm is being instantiated with auto-refresh
     *                                         in a Thread without a {@link android.os.Looper}
     * @throws RealmException                Other errors
     */
    @SuppressWarnings("UnusedDeclaration")
    @Deprecated
    public static Realm getInstance(Context context, String fileName, boolean autoRefresh) {
        return Realm.create(context.getFilesDir(), fileName, null, autoRefresh);
    }

    /**
     * Realm static constructor.
     * {link io.realm.close} must be called when you are done using the Realm instance.
     *
     * <strong>This constructor is now deprecated and will be removed in version 0.76.0.</strong>
     *
     * @param context an Android context
     * @param key     a 64-byte encryption key
     * @param autoRefresh whether the Realm object and its derived objects (RealmResults and RealmObjects)
     *                    should be automatically refreshed with the event loop (requires to be in a thread with a Looper)
     * @return an instance of the Realm class
     * @throws RealmMigrationNeededException The model classes have been changed and the Realm
     *                                       must be migrated
     * @throws RealmIOException              Error when accessing underlying file
     * @throws java.lang.IllegalStateException The Realm is being instantiated with auto-refresh
     *                                         in a Thread without a {@link android.os.Looper}
     * @throws RealmException                Other errors
     */
    @SuppressWarnings("UnusedDeclaration")
    @Deprecated
    public static Realm getInstance(Context context, byte[] key, boolean autoRefresh) {
        return Realm.getInstance(context, DEFAULT_REALM_NAME, key, autoRefresh);
    }

    /**
     * Realm static constructor.
     * {link io.realm.close} must be called when you are done using the Realm instance.
     *
     * It sets auto-refresh on if the current thread has a Looper, off otherwise.
     *
     * @param context an Android {@link android.content.Context}
     * @param key     a 64-byte encryption key
     * @return an instance of the Realm class
     * @throws RealmMigrationNeededException The model classes have been changed and the Realm
     *                                       must be migrated
     * @throws RealmIOException              Error when accessing underlying file
     * @throws java.lang.IllegalStateException The Realm is being instantiated in a Thread without
     *                                         a {@link android.os.Looper}
     * @throws RealmException                Other errors
     */
    @SuppressWarnings("UnusedDeclaration")
    public static Realm getInstance(Context context, byte[] key) {
        if (Looper.myLooper() != null) {
            return Realm.getInstance(context, DEFAULT_REALM_NAME, key, true);
        } else {
            return Realm.getInstance(context, DEFAULT_REALM_NAME, key, false);
        }
    }



    /**
     * Realm static constructor.
     * {link io.realm.close} must be called when you are done using the Realm instance.
     *
     * <strong>This constructor is now deprecated and will be removed in version 0.76.0.</strong>
     *
     * @param context  an Android {@link android.content.Context}
     * @param fileName the name of the file to save the Realm to
     * @param key      a 64-byte encryption key
     * @param autoRefresh whether the Realm object and its derived objects (RealmResults and RealmObjects)
     *                    should be automatically refreshed with the event loop (requires to be in a thread with a Looper)
     * @return an instance of the Realm class
     * @throws RealmMigrationNeededException The model classes have been changed and the Realm
     *                                       must be migrated
     * @throws RealmIOException              Error when accessing underlying file
     * @throws java.lang.IllegalStateException The Realm is being instantiated in a Thread without
     *                                         a {@link android.os.Looper}
     * @throws RealmException                Other errors
     */
    @Deprecated
    public static Realm getInstance(Context context, String fileName, byte[] key, boolean autoRefresh) {
        return Realm.create(context.getFilesDir(), fileName, key, autoRefresh);
    }

    /**
     * Realm static constructor.
     * {link io.realm.close} must be called when you are done using the Realm instance.
     *
     * <strong>This constructor is now deprecated and will be removed in version 0.76.0.</strong>
     *
     * @param writableFolder absolute path to a writable directory
     * @param key            a 64-byte encryption key
     * @param autoRefresh whether the Realm object and its derived objects (RealmResults and RealmObjects)
     *                    should be automatically refreshed with the event loop (requires to be in a thread with a Looper)
     * @return an instance of the Realm class
     * @throws RealmMigrationNeededException The model classes have been changed and the Realm
     *                                       must be migrated
     * @throws RealmIOException              Error when accessing underlying file
     * @throws java.lang.IllegalStateException The Realm is being instantiated in a Thread without
     *                                         a {@link android.os.Looper}
     * @throws RealmException                Other errors
     */
    @SuppressWarnings("UnusedDeclaration")
    @Deprecated
    public static Realm getInstance(File writableFolder, byte[] key, boolean autoRefresh) {
        return Realm.create(writableFolder, DEFAULT_REALM_NAME, key, autoRefresh);
    }

    /**
     * Realm static constructor.
     * {link io.realm.close} must be called when you are done using the Realm instance.
     *
     * <strong>This constructor is now deprecated and will be removed in version 0.76.0.</strong>
     *
     * @param writableFolder absolute path to a writable directory
     * @param filename       the name of the file to save the Realm to
     * @param key            a 64-byte encryption key
     * @param autoRefresh whether the Realm object and its derived objects (RealmResults and RealmObjects)
     *                    should be automatically refreshed with the event loop (requires to be in a thread with a Looper)
     * @return an instance of the Realm class
     * @throws RealmMigrationNeededException The model classes have been changed and the Realm
     *                                       must be migrated
     * @throws RealmIOException              Error when accessing underlying file
     * @throws java.lang.IllegalStateException The Realm is being instantiated in a Thread without
     *                                         a {@link android.os.Looper}
     * @throws RealmException                Other errors
     */
    @Deprecated
    public static Realm create(File writableFolder, String filename, byte[] key, boolean autoRefresh) {
        String absolutePath = new File(writableFolder, filename).getAbsolutePath();
        return createAndValidate(absolutePath, key, true, autoRefresh);
    }

    @SuppressWarnings("unchecked")
    private static Realm createAndValidate(String absolutePath, byte[] key, boolean validateSchema, boolean autoRefresh) {
        int id = absolutePath.hashCode();
        Map<Integer, Integer> localRefCount = referenceCount.get();
        Integer references = localRefCount.get(id);
        if (references == null) {
            references = 0;
        }
        Map<Integer, Realm> realms = realmsCache.get();
        Realm realm = realms.get(absolutePath.hashCode());

        if (realm != null) {
            localRefCount.put(id, references + 1);
            referenceCount.set(localRefCount);
            return realm;
        }

        realm = new Realm(absolutePath, key, autoRefresh);

        realms.put(absolutePath.hashCode(), realm);
        realmsCache.set(realms);

        if (validateSchema) {
            Class<?> validationClass;
            try {
                validationClass = Class.forName("io.realm.ValidationList");
            } catch (ClassNotFoundException e) {
                throw new RealmException("Could not find the generated ValidationList class: " + APT_NOT_EXECUTED_MESSAGE);
            }
            Method getProxyClassesMethod;
            try {
                getProxyClassesMethod = validationClass.getMethod("getProxyClasses");
            } catch (NoSuchMethodException e) {
                throw new RealmException("Could not find the getProxyClasses method in the ValidationList class: " + APT_NOT_EXECUTED_MESSAGE);
            }
            List<String> proxyClasses;
            try {
                //noinspection unchecked
                proxyClasses = (List<String>) getProxyClassesMethod.invoke(null);
            } catch (IllegalAccessException e) {
                throw new RealmException("Could not execute the getProxyClasses method in the ValidationList class: " + APT_NOT_EXECUTED_MESSAGE);
            } catch (InvocationTargetException e) {
                throw new RealmException("An exception was thrown in the getProxyClasses method in the ValidationList class: " + APT_NOT_EXECUTED_MESSAGE);
            }

            long version = realm.getVersion();
            boolean commitNeeded = false;
            try {
                realm.beginTransaction();
                if (version == UNVERSIONED) {
                    realm.setVersion(0);
                    commitNeeded = true;
                }

                for (String className : proxyClasses) {
                    String[] splitted = className.split("\\.");
                    String modelClassName = splitted[splitted.length - 1];
                    String generatedClassName = getProxyClassName(modelClassName);
                    Class<?> generatedClass;
                    try {
                        generatedClass = Class.forName(generatedClassName);
                    } catch (ClassNotFoundException e) {
                        throw new RealmException("Could not find the generated " + generatedClassName + " class: " + APT_NOT_EXECUTED_MESSAGE);
                    }

                    // if not versioned, create table
                    if (version == UNVERSIONED) {
                        Method initTableMethod;
                        try {
                            initTableMethod = generatedClass.getMethod("initTable", new Class[]{ImplicitTransaction.class});
                        } catch (NoSuchMethodException e) {
                            throw new RealmException("Could not find the initTable method in the generated " + generatedClassName + " class: " + APT_NOT_EXECUTED_MESSAGE);
                        }
                        try {
                            initTableMethod.invoke(null, realm.transaction);
                            commitNeeded = true;
                        } catch (IllegalAccessException e) {
                            throw new RealmException("Could not execute the initTable method in the " + generatedClassName + " class: " + APT_NOT_EXECUTED_MESSAGE);
                        } catch (InvocationTargetException e) {
                            throw new RealmException("An exception was thrown in the initTable method in the " + generatedClassName + " class: " + APT_NOT_EXECUTED_MESSAGE);
                        }
                    }

                    // validate created table
                    Method validateMethod;
                    try {
                        validateMethod = generatedClass.getMethod("validateTable", new Class[]{ImplicitTransaction.class});
                    } catch (NoSuchMethodException e) {
                        throw new RealmException("Could not find the validateTable method in the generated " + generatedClassName + " class: " + APT_NOT_EXECUTED_MESSAGE);
                    }
                    try {
                        validateMethod.invoke(null, realm.transaction);
                    } catch (IllegalAccessException e) {
                        throw new RealmException("Could not execute the validateTable method in the " + generatedClassName + " class: " + APT_NOT_EXECUTED_MESSAGE);
                    } catch (InvocationTargetException e) {
                        throw new RealmMigrationNeededException(e.getMessage(), e);
                    }

                    // Populate the columnIndices table
                    Method fieldNamesMethod;
                    try {
                        fieldNamesMethod = generatedClass.getMethod("getFieldNames");
                    } catch (NoSuchMethodException e) {
                        throw new RealmException("Could not find the getFieldNames method in the generated " + generatedClassName + " class: " + APT_NOT_EXECUTED_MESSAGE);
                    }
                    List<String> fieldNames;
                    try {
                        //noinspection unchecked
                        fieldNames = (List<String>) fieldNamesMethod.invoke(null);
                    } catch (IllegalAccessException e) {
                        throw new RealmException("Could not execute the getFieldNames method in the generated " + generatedClassName + " class: " + APT_NOT_EXECUTED_MESSAGE);
                    } catch (InvocationTargetException e) {
                        throw new RealmException("An exception was thrown in the getFieldNames method in the generated " + generatedClassName + " class: " + APT_NOT_EXECUTED_MESSAGE);
                    }
                    Table table = realm.transaction.getTable(TABLE_PREFIX + modelClassName);
                    for (String fieldName : fieldNames) {
                        long columnIndex = table.getColumnIndex(fieldName);
                        if (columnIndex == -1) {
                            throw new RealmMigrationNeededException("Field '" + fieldName + "' not found for type '" + modelClassName + "'");
                        }
                        Map<String, Long> innerMap = columnIndices.get(modelClassName);
                        if (innerMap == null) {
                            innerMap = new HashMap<String, Long>();
                        }
                        innerMap.put(fieldName, columnIndex);
                        columnIndices.put(modelClassName, innerMap);
                    }
                }
            } finally {
                if (commitNeeded) {
                    realm.commitTransaction();
                } else {
                    realm.cancelTransaction();
                }
            }
        }

        localRefCount.put(id, references + 1);
        referenceCount.set(localRefCount);
        return realm;
    }

    /**
     * Create a Realm object for each object in a JSON array. This must be done inside a transaction.
     * JSON properties with a null value will map to the default value for the data type in Realm
     * and unknown properties will be ignored.
     *
     * @param clazz Type of Realm objects to create.
     * @param json  Array where each JSONObject must map to the specified class.
     *
     * @throws RealmException if mapping from JSON fails.
     */
    public <E extends RealmObject> void createAllFromJson(Class<E> clazz, JSONArray json) {
        if (clazz == null || json == null) return;

        for (int i = 0; i < json.length(); i++) {
            E obj = createObject(clazz);
            try {
                obj.populateUsingJsonObject(json.getJSONObject(i));
            } catch (Exception e) {
                throw new RealmException("Could not map Json", e);
            }
        }
    }

    /**
     * Create a Realm object for each object in a JSON array. This must be done inside a transaction.
     * JSON properties with a null value will map to the default value for the data type in Realm
     * and unknown properties will be ignored.
     *
     * @param clazz Type of Realm objects to create.
     * @param json  JSON array as a String where each object can map to the specified class.
     *
     * @throws RealmException if mapping from JSON fails.
     */
    public <E extends RealmObject> void createAllFromJson(Class<E> clazz, String json) {
        if (clazz == null || json == null || json.length() == 0) return;

        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception e) {
            throw new RealmException("Could not create JSON array from string", e);
        }

        createAllFromJson(clazz, arr);
    }

    /**
     * Create a Realm object for each object in a JSON array. This must be done inside a transaction.
     * JSON properties with a null value will map to the default value for the data type in Realm
     * and unknown properties will be ignored.
     *
     * @param clazz         Type of Realm objects created.
     * @param inputStream   JSON array as a InputStream. All objects in the array must be of the
     *                      specified class.
     *
     * @throws RealmException if mapping from JSON fails.
     * @throws IOException if something was wrong with the input stream.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public <E extends RealmObject> void createAllFromJson(Class<E> clazz, InputStream inputStream) throws IOException {
        if (clazz == null || inputStream == null) return;

        JsonReader reader = new JsonReader(new InputStreamReader(inputStream, "UTF-8"));
        try {
            reader.beginArray();
            while (reader.hasNext()) {
                E obj = createObject(clazz);
                obj.populateUsingJsonStream(reader);
            }
            reader.endArray();
        } finally {
            reader.close();
        }
    }

    /**
     * Create a Realm object prefilled with data from a JSON object. This must be done inside a
     * transaction. JSON properties with a null value will map to the default value for the data
     * type in Realm and unknown properties will be ignored.
     *
     * @param clazz Type of Realm object to create.
     * @param json  JSONObject with object data.
     * @return Created object or null if no json data was provided.
     *
     * @throws RealmException if the mapping from JSON fails.
     */
    public <E extends RealmObject> E createObjectFromJson(Class<E> clazz, JSONObject json) {
        if (clazz == null || json == null) return null;

        E obj = createObject(clazz);
        try {
            obj.populateUsingJsonObject(json);
        } catch (Exception e) {
            throw new RealmException("Could not map Json", e);
        }

        return obj;
    }

    /**
     * Create a Realm object prefilled with data from a JSON object. This must be done inside a
     * transaction. JSON properties with a null value will map to the default value for the data
     * type in Realm and unknown properties will be ignored.
     *
     * @param clazz Type of Realm object to create.
     * @param json  JSON string with object data.
     * @return Created object or null if json string was empty or null.
     *
     * @throws RealmException if mapping to json failed.
     */
    public <E extends RealmObject> E createObjectFromJson(Class<E> clazz, String json) {
        if (clazz == null || json == null || json.length() == 0) return null;

        JSONObject obj;
        try {
            obj = new JSONObject(json);
        } catch (Exception e) {
            throw new RealmException("Could not create Json object from string", e);
        }

        return createObjectFromJson(clazz, obj);
    }

    /**
     * Create a Realm object prefilled with data from a JSON object. This must be done inside a
     * transaction. JSON properties with a null value will map to the default value for the data
     * type in Realm and unknown properties will be ignored.
     *
     * @param clazz         Type of Realm object to create.
     * @param inputStream   JSON object data as a InputStream.
     * @return Created object or null if json string was empty or null.
     *
     * @throws RealmException if the mapping from JSON failed.
     * @throws IOException if something was wrong with the input stream.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public <E extends RealmObject> E createObjectFromJson(Class<E> clazz, InputStream inputStream) throws IOException {
        if (inputStream == null || clazz == null) return null;

        JsonReader reader = new JsonReader(new InputStreamReader(inputStream, "UTF-8"));
        try {
            E obj = createObject(clazz);
            obj.populateUsingJsonStream(reader);
            return obj;
        } finally {
            reader.close();
        }
    }

    /**
     * Write a compacted copy of the Realm to the given destination File.
     *
     * The destination file cannot already exist.
     *
     * Note that if this is called from within a write transaction it writes the
     * current data, and not the data as it was when the last write transaction was committed.
     *
     * @param destination File to save the Realm to
     * @throws java.io.IOException if any write operation fails
     */
    public void writeCopyTo(File destination) throws IOException {
        checkIfValid();
        transaction.writeToFile(destination.getAbsolutePath());
    }


    /**
     * Instantiates and adds a new object to the realm
     *
     * @param clazz The Class of the object to create
     * @return The new object
     * @throws RealmException An object could not be created
     */
    public <E extends RealmObject> E createObject(Class<E> clazz) {
        Table table;
        table = tables.get(clazz);
        if (table == null) {
            String simpleClassName = simpleClassNames.get(clazz);
            if (simpleClassName == null) {
                simpleClassName = clazz.getSimpleName();
                simpleClassNames.put(clazz, simpleClassName);
            }
            String generatedClassName = getProxyClassName(simpleClassName);

            Class<?> generatedClass = generatedClasses.get(generatedClassName);
            if (generatedClass == null) {
                try {
                    generatedClass = Class.forName(generatedClassName);
                } catch (ClassNotFoundException e) {
                    throw new RealmException("Could not find the generated proxy class: " + APT_NOT_EXECUTED_MESSAGE);
                }
                generatedClasses.put(generatedClassName, generatedClass);
            }

            Method method = initTableMethods.get(generatedClass);
            if (method == null) {
                try {
                    method = generatedClass.getMethod("initTable", new Class[]{ImplicitTransaction.class});
                } catch (NoSuchMethodException e) {
                    throw new RealmException("Could not find the initTable() method in generated proxy class: " + APT_NOT_EXECUTED_MESSAGE);
                }
                initTableMethods.put(generatedClass, method);
            }

            try {
                table = (Table) method.invoke(null, transaction);
                tables.put(clazz, table);
            } catch (IllegalAccessException e) {
                throw new RealmException("Could not launch the initTable method: " + APT_NOT_EXECUTED_MESSAGE);
            } catch (InvocationTargetException e) {
                e.printStackTrace();
                throw new RealmException("An exception occurred while running the initTable method: " + APT_NOT_EXECUTED_MESSAGE);
            }
        }

        long rowIndex = table.addEmptyRow();
        return get(clazz, rowIndex);
    }

    <E> void remove(Class<E> clazz, long objectIndex) {
        getTable(clazz).moveLastOver(objectIndex);
    }

    @SuppressWarnings("unchecked")
    <E extends RealmObject> E get(Class<E> clazz, long rowIndex) {
        E result;

        Table table = tables.get(clazz);
        if (table == null) {
            String simpleClassName = simpleClassNames.get(clazz);
            if (simpleClassName == null) {
                simpleClassName = clazz.getSimpleName();
                simpleClassNames.put(clazz, simpleClassName);
            }

            table = transaction.getTable(TABLE_PREFIX + simpleClassName);
            tables.put(clazz, table);
        }

        Row row = table.getRow(rowIndex);

        Constructor constructor = generatedConstructors.get(clazz);
        if (constructor == null) {
            String simpleClassName = simpleClassNames.get(clazz);
            if (simpleClassName == null) {
                simpleClassName = clazz.getSimpleName();
                simpleClassNames.put(clazz, simpleClassName);
            }
            String generatedClassName = getProxyClassName(simpleClassName);


            Class<?> generatedClass = generatedClasses.get(generatedClassName);
            if (generatedClass == null) {
                try {
                    generatedClass = Class.forName(generatedClassName);
                } catch (ClassNotFoundException e) {
                    throw new RealmException("Could not find the generated proxy class: " + APT_NOT_EXECUTED_MESSAGE);
                }
                generatedClasses.put(generatedClassName, generatedClass);
            }

            constructor = constructors.get(generatedClass);
            if (constructor == null) {
                try {
                    constructor = generatedClass.getConstructor();
                } catch (NoSuchMethodException e) {
                    throw new RealmException("Could not find the constructor in generated proxy class: " + APT_NOT_EXECUTED_MESSAGE);
                }
                constructors.put(generatedClass, constructor);
                generatedConstructors.put(clazz, constructor);
            }
        }

        try {
            // We are know the casted type since we generated the class
            result = (E) constructor.newInstance();
        } catch (InstantiationException e) {
            throw new RealmException("Could not instantiate the proxy class");
        } catch (IllegalAccessException e) {
            throw new RealmException("Could not run the constructor of the proxy class");
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            throw new RealmException("An exception occurred while instantiating the proxy class");
        }
        result.row = row;
        result.realm = this;
        return result;
    }

    private static String getProxyClassName(String simpleClassName) {
        return "io.realm." + simpleClassName + "RealmProxy";
    }

    boolean contains(Class<?> clazz) {
        String simpleClassName = simpleClassNames.get(clazz);
        if (simpleClassName == null) {
            simpleClassName = clazz.getSimpleName();
            simpleClassNames.put(clazz, simpleClassName);
        }
        return transaction.hasTable(TABLE_PREFIX + simpleClassName);
    }

    /**
     * Returns a typed RealmQuery, which can be used to query for specific objects of this type
     *
     * @param clazz The class of the object which is to be queried for
     * @return A typed RealmQuery, which can be used to query for specific objects of this type
     * @throws java.lang.RuntimeException Any other error
     * @see io.realm.RealmQuery
     */
    public <E extends RealmObject> RealmQuery<E> where(Class<E> clazz) {
        checkIfValid();
        return new RealmQuery<E>(this, clazz);
    }

    /**
     * Get all objects of a specific Class
     *
     * @param clazz the Class to get objects of
     * @return A RealmResult list containing the objects
     * @throws java.lang.RuntimeException Any other error
     * @see io.realm.RealmResults
     */
    public <E extends RealmObject> RealmResults<E> allObjects(Class<E> clazz) {
        return where(clazz).findAll();
    }

    /**
     * Get all objects of a specific Class sorted by specific field name.
     *
     * @param clazz the Class to get objects of.
     * @param fieldName the field name to sort by.
     * @param sortAscending sort ascending if SORT_ORDER_ASCENDING, sort descending if SORT_ORDER_DESCENDING.
     * @return A sorted RealmResults containing the objects.
     * @throws java.lang.IllegalArgumentException if field name does not exist.
     */
    public <E extends RealmObject> RealmResults<E> allObjects(Class<E> clazz, String fieldName, boolean sortAscending) {
        checkIfValid();
        Table table = getTable(clazz);
        TableView.Order order = sortAscending ? TableView.Order.ascending : TableView.Order.descending;
        Long columnIndex = columnIndices.get(simpleClassNames.get(clazz)).get(fieldName);
        if (columnIndex == null || columnIndex < 0) {
            throw new IllegalArgumentException(String.format("Field name '%s' does not exist.", fieldName));
        }

        TableView tableView = table.getSortedView(columnIndex, order);
        return new RealmResults<E>(this, tableView, clazz);
    }

    // Notifications

    /**
     * Add a change listener to the Realm
     *
     * @param listener the change listener
     * @see io.realm.RealmChangeListener
     */
    public void addChangeListener(RealmChangeListener listener) {
        checkIfValid();
        changeListeners.add(listener);
    }

    /**
     * Remove the specified change listener
     *
     * @param listener the change listener to be removed
     * @see io.realm.RealmChangeListener
     */
    public void removeChangeListener(RealmChangeListener listener) {
        checkIfValid();
        changeListeners.remove(listener);
    }

    /**
     * Remove all user-defined change listeners
     *
     * @see io.realm.RealmChangeListener
     */
    public void removeAllChangeListeners() {
        checkIfValid();
        changeListeners.clear();
    }

    void sendNotifications() {
        List<RealmChangeListener> defensiveCopy = new ArrayList<RealmChangeListener>(changeListeners);
        for (RealmChangeListener listener : defensiveCopy) {
            listener.onChange();
        }
    }

    @SuppressWarnings("UnusedDeclaration")
    boolean hasChanged() {
        return sharedGroup.hasChanged();
    }

    /**
     * Transactions
     */

    /**
     * Refresh the Realm instance and all the RealmResults and RealmObjects instances coming from it
     */
    @SuppressWarnings("UnusedDeclaration")
    public void refresh() {
        checkIfValid();
        transaction.advanceRead();
    }

    /**
     * Starts a write transaction, this must be closed with {@link io.realm.Realm#commitTransaction()}
     * or aborted by @{link io.realm.Realm#cancelTransaction()}. Write transactions are used to
     * atomically create, update and delete objects within a realm.
     * <br>
     * Before beginning the write transaction, @{link io.realm.Realm#beginTransaction()} updates the
     * realm in the case of pending updates from other threads.
     * <br>
     * Notice: it is not possible to nest write transactions. If you start a write
     * transaction within a write transaction an exception is thrown.
     * <br>
     * @throws java.lang.IllegalStateException If already in a write transaction or incorrect thread.
     *
     */
    public void beginTransaction() {
        checkIfValid();
        transaction.promoteToWrite();
    }

    /**
     * All changes since @{link io.realm.Realm#beginTransaction()} are persisted to disk and the
     * realm reverts back to being read-only. An event is sent to notify all other realm instances
     * that a change has occurred. When the event is received, the other realms will get their
     * objects and @{link io.realm.RealmResults} updated to reflect
     * the changes from this commit.
     * 
     * @throws java.lang.IllegalStateException If the write transaction is in an invalid state or incorrect thread.
     */
    public void commitTransaction() {
        checkIfValid();
        transaction.commitAndContinueAsRead();

        for (Map.Entry<Handler, Integer> handlerIntegerEntry : handlers.entrySet()) {
            Handler handler = handlerIntegerEntry.getKey();
            int realmId = handlerIntegerEntry.getValue();
            if (
                    realmId == id                                // It's the right realm
                    && !handler.hasMessages(REALM_CHANGED)       // The right message
                    && handler.getLooper().getThread().isAlive() // The receiving thread is alive
                    && !handler.equals(this.handler)             // Don't notify yourself
            ) {
                handler.sendEmptyMessage(REALM_CHANGED);
            }
        }
        sendNotifications();
    }

    /**
     * Revert all writes (created, updated, or deleted objects) made in the current write
     * transaction and end the transaction.
     * <br>
     * The realm reverts back to read-only.
     * <br>
     * Calling this when not in a write transaction will throw an exception.
     *
     * @throws java.lang.IllegalStateException    If the write transaction is an invalid state,
    *                                             not in a write transaction or incorrect thread.
    */
     public void cancelTransaction() {
         checkIfValid();
         transaction.rollbackAndContinueAsRead();
     }

    /**
     * Executes a given transaction on the Realm. {@link #beginTransaction()} and
     * {@link #commitTransaction()} will be called automatically. If any exception is thrown
     * during the transaction {@link #cancelTransaction()} will be called instead.
     *
     * @param transaction Transaction to execute.
     * @throws RealmException if any error happened during the transaction.
     */
    public void executeTransaction(Transaction transaction) {
        if (transaction == null) return;
        beginTransaction();
        try {
            transaction.execute(this);
            commitTransaction();
        } catch (RuntimeException e) {
            cancelTransaction();
            throw new RealmException("Error during transaction.", e);
        } catch (Error e) {
            cancelTransaction();
            throw e;
        }
    }

    /**
     * Remove all objects of the specified class.
     *
     * @param classSpec The class which objects should be removed
     * @throws java.lang.RuntimeException Any other error
     */
    public void clear(Class<?> classSpec) {
        getTable(classSpec).clear();
    }

    // package protected so unit tests can access it
    long getVersion() {
        if (!transaction.hasTable("metadata")) {
            return UNVERSIONED;
        }
        Table metadataTable = transaction.getTable("metadata");
        return metadataTable.getLong(0, 0);
    }

    // package protected so unit tests can access it
    void setVersion(long version) {
        Table metadataTable = transaction.getTable("metadata");
        if (metadataTable.getColumnCount() == 0) {
            metadataTable.addColumn(ColumnType.INTEGER, "version");
            metadataTable.addEmptyRow();
        }
        metadataTable.setLong(0, 0, version);
    }

    @SuppressWarnings("UnusedDeclaration")
    public static void migrateRealmAtPath(String realmPath, RealmMigration migration) {
        migrateRealmAtPath(realmPath, null, migration, true);
    }

    public static void migrateRealmAtPath(String realmPath, byte[] key, RealmMigration migration) {
        migrateRealmAtPath(realmPath, key, migration, true);
    }

    @SuppressWarnings("UnusedDeclaration")
    public static void migrateRealmAtPath(String realmPath, RealmMigration migration, boolean autoRefresh) {
        migrateRealmAtPath(realmPath, null, migration, autoRefresh);
    }

    public static void migrateRealmAtPath(String realmPath, byte[] key, RealmMigration migration, boolean autoUpdate) {
        Realm realm = Realm.createAndValidate(realmPath, key, false, autoUpdate);
        realm.beginTransaction();
        realm.setVersion(migration.execute(realm, realm.getVersion()));
        realm.commitTransaction();
        realm.close();

        realmsCache.remove();
    }

    /**
     * Delete the Realm file from the filesystem for the default Realm (named "default.realm").
     * The realm must be unused and closed before calling this method.
     * WARNING: Your Realm must not be open (typically when your app launch).
     *
     * @param context an Android context.
     * @return false if a file could not be deleted. The failing file will be logged.
     * @see io.realm.Realm#clear(Class)
     */
    public static boolean deleteRealmFile(Context context) {
        return deleteRealmFile(context, DEFAULT_REALM_NAME);
    }

    /**
     * Delete the Realm file from the filesystem for a custom named Realm.
     * The realm must be unused and closed before calling this method.
     *
     * @param context  an Android @{{@link android.content.Context}.
     * @param fileName the name of the custom Realm (i.e. "myCustomRealm.realm").
     * @return false if a file could not be deleted. The failing file will be logged.
     */
    public static boolean deleteRealmFile(Context context, String fileName) {
        boolean result = true;
        File writableFolder = context.getFilesDir();
        List<File> filesToDelete = Arrays.asList(
                new File(writableFolder, fileName),
                new File(writableFolder, fileName + ".lock"));
        for (File fileToDelete : filesToDelete) {
            if (fileToDelete.exists()) {
                boolean deleteResult = fileToDelete.delete();
                if (!deleteResult) {
                    result = false;
                    Log.w(TAG, "Could not delete the file " + fileToDelete);
                }
            }
        }
        return result;
    }

    /**
     * Compact a realm file. A realm file usually contain free/unused space.
     * This method removes this free space and the file size is thereby reduced.
     * Objects within the realm files are untouched.
     * 
     * The file must be closed before this method is called.
     * The file system should have free space for at least a copy of the realm file.
     * The realm file is left untouched if any file operation fails. 
     *
     * @param context an Android {@link android.content.Context}
     * @param fileName the name of the file to compact
     * @return true if successful, false if any file operation failed
     */
    public static boolean compactRealmFile(Context context, String fileName) {
        File realmFile = new File(context.getFilesDir(), fileName);
        File tmpFile = new File(
                context.getFilesDir(),
                String.valueOf(System.currentTimeMillis()) + UUID.randomUUID() + ".realm");

        Realm realm = null;
        try {
            realm = Realm.getInstance(context, fileName);
            realm.writeCopyTo(tmpFile);
            if (!realmFile.delete()) {
                return false;
            }
            if (!tmpFile.renameTo(realmFile)) {
                return false;
            }
        } catch (IOException e) {
            return false;
        } finally {
            if (realm != null) {
                realm.close();
            }
        }
        return true;
    }

    /**
     * Compact a realm file. A realm file usually contain free/unused space.
     * This method removes this free space and the file size is thereby reduced.
     * Objects within the realm files are untouched.
     * 
     * The file must be closed before this method is called.
     * The file system should have free space for at least a copy of the realm file.
     * The realm file is left untouched if any file operation fails. 
     *
     * @param context an Android {@link android.content.Context}
     * @return true if successful, false if any file operation failed
     */
    public static boolean compactRealmFile(Context context) {
        return compactRealmFile(context, DEFAULT_REALM_NAME);
    }

    /**
     * Returns the absolute path to where this Realm is persisted on disk.
     *
     * @return The absolute path to the realm file.
     */
    public String getPath() {
        return path;
    }

    /**
     * Encapsulates a Realm transaction.
     *
     * Using this class will automatically handle {@link #beginTransaction()} and {@link #commitTransaction()}
     * If any exception is thrown while inside the transaction {@link #cancelTransaction()} will be called instead.
     */
    public interface Transaction {
        public void execute(Realm realm);
    }
}

