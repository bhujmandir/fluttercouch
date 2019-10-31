package it.oltrenuovefrontiere.fluttercouch;

import android.content.res.AssetManager;

import com.couchbase.lite.BasicAuthenticator;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.DatabaseConfiguration;
import com.couchbase.lite.Document;
import com.couchbase.lite.Endpoint;
import com.couchbase.lite.ListenerToken;
import com.couchbase.lite.LogFileConfiguration;
import com.couchbase.lite.LogLevel;
import com.couchbase.lite.MutableDocument;
import com.couchbase.lite.Replicator;
import com.couchbase.lite.ReplicatorConfiguration;
import com.couchbase.lite.SessionAuthenticator;
import com.couchbase.lite.URLEndpoint;
import com.couchbase.lite.Query;
import com.couchbase.lite.IndexBuilder;
import com.couchbase.lite.FullTextIndexItem;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


import android.content.Context;
import java.io.OutputStreamWriter;

class CBManager {
    private HashMap<String, Database> mDatabase = new HashMap<>();
    private HashMap<String, Query> mQueries = new HashMap<>();
    private HashMap<String, ListenerToken> mQueryListenerTokens = new HashMap<>();
    private ReplicatorConfiguration mReplConfig;
    private Replicator mReplicator;
    private String defaultDatabase = "defaultDatabase";
    private DatabaseConfiguration mDBConfig;
    private CBManagerDelegate mDelegate;

    public CBManager(CBManagerDelegate delegate, boolean enableLogging) {
        mDelegate = delegate;
        mDBConfig = new DatabaseConfiguration(mDelegate.getContext());

        if (enableLogging) {
            final File path = mDelegate.getContext().getCacheDir();
            Database.log.getFile().setConfig(new LogFileConfiguration(path.toString()));
            Database.log.getFile().setLevel(LogLevel.INFO);
        }
    }

    public Database getDatabase() {
        return mDatabase.get(defaultDatabase);
    }

    public Database getDatabase(String name) {
        if (mDatabase.containsKey(name)) {
            return mDatabase.get(name);
        }
        return null;
    }

    public String saveDocument(Map<String, Object> _map) throws CouchbaseLiteException {
        MutableDocument mutableDoc = new MutableDocument(_map);
        mDatabase.get(defaultDatabase).save(mutableDoc);
        return mutableDoc.getId();
    }

    public String saveDocumentWithId(String _id, Map<String, Object> _map) throws CouchbaseLiteException {
        MutableDocument mutableDoc = new MutableDocument(_id, _map);
        mDatabase.get(defaultDatabase).save(mutableDoc);
        return mutableDoc.getId();
    }

    public Map<String, Object> getDocumentWithId(String _id) throws CouchbaseLiteException {
        Database defaultDb = getDatabase();
        HashMap<String, Object> resultMap = new HashMap<String, Object>();
        if (defaultDatabase != null) {
            try {
                Document document = defaultDb.getDocument(_id);
                if (document != null) {
                    resultMap.put("doc", document.toMap());
                    resultMap.put("id", _id);
                } else {
                    resultMap.put("doc", null);
                    resultMap.put("id", _id);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return resultMap;
    }

    public void initDatabaseWithName(String _name) throws CouchbaseLiteException {
        if (!mDatabase.containsKey(_name)) {
            defaultDatabase = _name;

            File dbFile = new File(mDelegate.getContext().getFilesDir().toString(), _name + ".cblite2");

            if (!dbFile.exists()) {
                try {
                    AssetManager assetManager = mDelegate.getAssets();
                    File path = new File(mDelegate.getContext().getFilesDir().toString());
                    unzip(assetManager.open(_name + ".cblite2.zip"), path);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            Database db = new Database(_name, mDBConfig);
            mDatabase.put(_name, db);
        }
    }

    public void createIndex(String _indexName, String[] properties) throws CouchbaseLiteException {
        Database db = getDatabase();

        List<FullTextIndexItem> propertyList = new ArrayList<FullTextIndexItem>();

        for (String property : properties) {
            propertyList.add(FullTextIndexItem.property(property));
        }

        FullTextIndexItem[] propertyArray = new FullTextIndexItem[propertyList.size()];
        propertyArray = propertyList.toArray(propertyArray);

        db.createIndex(_indexName, IndexBuilder.fullTextIndex(propertyArray).ignoreAccents(true));
    }

    public void deleteDatabaseWithName(String _name) throws CouchbaseLiteException {
        Database.delete(_name,new File(mDBConfig.getDirectory()));
    }

    public void closeDatabaseWithName(String _name) throws CouchbaseLiteException {
        Database _db = mDatabase.remove(_name);
        if (_db != null) {
            _db.close();
        }
    }

    public void setReplicatorEndpoint(String _endpoint) throws URISyntaxException {
        Endpoint targetEndpoint = new URLEndpoint(new URI(_endpoint));
        mReplConfig = new ReplicatorConfiguration(mDatabase.get(defaultDatabase), targetEndpoint);
    }

    public void setReplicatorType(String _type) throws CouchbaseLiteException {
        ReplicatorConfiguration.ReplicatorType settedType = ReplicatorConfiguration.ReplicatorType.PULL;
        if (_type.equals("PUSH")) {
            settedType = ReplicatorConfiguration.ReplicatorType.PUSH;
        } else if (_type.equals("PULL")) {
            settedType = ReplicatorConfiguration.ReplicatorType.PULL;
        } else if (_type.equals("PUSH_AND_PULL")) {
            settedType = ReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL;
        }
        mReplConfig.setReplicatorType(settedType);
    }

    public void setReplicatorBasicAuthentication(Map<String, String> _auth) throws Exception {
        if (_auth.containsKey("username") && _auth.containsKey("password")) {
            mReplConfig.setAuthenticator(new BasicAuthenticator(_auth.get("username"), _auth.get("password")));
        } else {
            throw new Exception();
        }
    }

    public void setReplicatorSessionAuthentication(String sessionID) throws Exception {
        if (sessionID != null) {
            mReplConfig.setAuthenticator(new SessionAuthenticator(sessionID));
        } else {
            throw new Exception();
        }
    }

    public void setReplicatorPinnedServerCertificate(String assetKey) throws Exception {
        if (assetKey != null) {
            AssetManager assetManager = mDelegate.getAssets();
            String fileKey = mDelegate.lookupKeyForAsset(assetKey);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream is = assetManager.open(fileKey)) {
                int nRead;
                byte[] data = new byte[1024];
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }

                buffer.flush();
            }

            mReplConfig.setPinnedServerCertificate(buffer.toByteArray());
        } else {
            throw new Exception();
        }
    }

    public void setReplicatorContinuous(boolean _continuous) {
        mReplConfig.setContinuous(_continuous);
    }

    public void initReplicator() {
        mReplicator = new Replicator(mReplConfig);
    }

    public void startReplicator() {
        mReplicator.start();
    }

    public void stopReplicator() {
        mReplicator.stop();
        mReplicator = null;
    }

    public long getDocumentCount() throws Exception {
        Database defaultDb = getDatabase();
        if (defaultDb != null) {
            return defaultDb.getCount();
        } else {
            throw new Exception();
        }
    }

    public Replicator getReplicator() {
        return mReplicator;
    }

    void addQuery(String queryId, Query query, ListenerToken token) {
        mQueries.put(queryId,query);
        mQueryListenerTokens.put(queryId,token);
    }

    Query getQuery(String queryId) {
        return mQueries.get(queryId);
    }

    Query removeQuery(String queryId) {
        Query query = mQueries.remove(queryId);
        ListenerToken token = mQueryListenerTokens.remove(queryId);
        if (query != null && token != null) {
            query.removeChangeListener(token);
        }

        return query;
    }

    private static void unzip(InputStream in, File destination) throws IOException {
        byte[] buffer = new byte[1024];

        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry ze = zis.getNextEntry();

        while (ze != null) {
            String fileName = ze.getName();
            File newFile = new File(destination, fileName);

            if (ze.isDirectory()) {
                newFile.mkdirs();
            } else {
                new File(newFile.getParent()).mkdirs();

                FileOutputStream fos = new FileOutputStream(newFile);

                int len;

                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }

                fos.close();
            }

            ze = zis.getNextEntry();
        }

        zis.closeEntry();
        zis.close();

        in.close();
    }
}
