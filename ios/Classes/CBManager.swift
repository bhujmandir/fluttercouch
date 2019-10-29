//
//  CBManager.swift
//  Runner
//
//  Created by Luca Christille on 14/08/18.
//  Copyright © 2018 The Chromium Authors. All rights reserved.
//

import Foundation
import CouchbaseLiteSwift

typealias ExpressionJson = Array<Dictionary<String,Any>>

enum CBManagerError: Error {
    case CertificatePinning
}

protocol CBManagerDelegate : class {
    func lookupKey(forAsset assetKey: String) -> String?
}

class CBManager {
    private var mDatabase : Dictionary<String, Database> = Dictionary();
    private var mQueries : Dictionary<String,Query> = Dictionary();
    private var mQueryListenerTokens : Dictionary<String,ListenerToken> = Dictionary();
    private var mReplConfig : ReplicatorConfiguration?;
    private var mReplicator : Replicator?;
    private var defaultDatabase = "defaultDatabase";
    private var mDBConfig = DatabaseConfiguration();
    private weak var mDelegate: CBManagerDelegate?
    fileprivate var _applicationSupportDirectory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).last
    
    init(delegate: CBManagerDelegate, enableLogging: Bool) {
        mDelegate = delegate
        
        guard enableLogging else {
            return
        }
        
        let tempFolder = NSTemporaryDirectory().appending("cbllog")
        Database.log.file.config = LogFileConfiguration(directory: tempFolder)
        Database.log.file.level = .info
    }
    
    func getDatabase() -> Database? {
        if let result = mDatabase[defaultDatabase] {
            return result;
        } else {
            return nil;
        }
    }
    
    func getDatabase(name : String) -> Database? {
        if let result = mDatabase[name] {
            return result;
        } else {
            return nil;
        }
    }
    
    func saveDocument(map: Dictionary<String, Any>) throws -> String? {
        let mutableDocument: MutableDocument = MutableDocument(data: map);
        try mDatabase[defaultDatabase]?.saveDocument(mutableDocument)
        return mutableDocument.id;
    }
    
    func saveDocumentWithId(id : String, map: Dictionary<String, Any>) throws -> String? {
        let mutableDocument: MutableDocument = MutableDocument(id: id, data: map)
        try mDatabase[defaultDatabase]?.saveDocument(mutableDocument)
        return mutableDocument.id
    }
    
    func getDocumentWithId(id : String) -> NSDictionary? {
        let resultMap: NSMutableDictionary = NSMutableDictionary.init()
        if let defaultDb: Database = getDatabase() {
            if let document: Document = defaultDb.document(withID: id) {
                let retrievedDocument: NSDictionary = NSDictionary.init(dictionary: document.toDictionary())
                // It is a repetition due to implementation of Document Dart Class
                resultMap["id"] = id
                resultMap["doc"] = retrievedDocument
            } else {
                resultMap["id"] = id
                resultMap["doc"] = NSDictionary.init()
            }
        }
        return NSDictionary.init(dictionary: resultMap)
    }

    func initDatabaseWithName(name: String) throws {
        if mDatabase.keys.contains(name) {
            defaultDatabase = name
        } else {
            guard let assetFolderUrl = _applicationSupportDirectory else {
                fatalError("Could not open Application Support Directory for app!")
                return
            }
            let assetFolderPath = assetFolderUrl.path
            let fileManager = FileManager.default
            if !fileManager.fileExists(atPath: assetFolderPath) {
                try fileManager.createDirectory(atPath: assetFolderPath,
                                                withIntermediateDirectories: true,
                                                attributes: nil)
                
            }

            // Set the folder path for the CBLite DB
            mDBConfig.directory = assetFolderPath

            // Load the prebuilt database if it exists at the specified folder
            if Database.exists(withName: name, inDirectory: assetFolderPath) == false {
                // Load prebuilt database from App Bundle and copy over to Applications support path
                if let prebuiltPath = Bundle.main.path(forResource: name, ofType: "cblite2") {
                    try Database.copy(fromPath: prebuiltPath, toDatabase: "\(name)", withConfig: mDBConfig)
                    
                }
                // Get handle to DB  specified path
                let newDatabase = try Database(name: name, config: mDBConfig)
                mDatabase[name] = newDatabase
            }
            else
            {
                // Gets handle to existing DB at specified path
                let newDatabase = try Database(name: name, config: mDBConfig)
                mDatabase[name] = newDatabase
            }

            defaultDatabase = name
        }
    }

    func createIndex(name: String, properties: [String]) throws {
        guard let db: Database = getDatabase() else {fatalError("db is not initialized at this point!")}

        var propertyList = [FullTextIndexItem]()

        for property in properties {
            propertyList.append(FullTextIndexItem.property(property))
        }

        try db.createIndex(
            IndexBuilder.fullTextIndex(items: propertyList).ignoreAccents(true), withName: name)

    }

    func deleteDatabaseWithName(name: String) throws {
        try Database.delete(withName: name)
    }
    
    func closeDatabaseWithName(name: String) throws {
        if let _db = mDatabase.removeValue(forKey: name) {
            try _db.close()
        }
    }
    
    func addQuery(queryId: String, query: Query, listenerToken: ListenerToken) {
        mQueries[queryId] = query;
        mQueryListenerTokens[queryId] = listenerToken;
    }
    
    func getQuery(queryId: String) -> Query? {
        return mQueries[queryId]
    }
    
    func removeQuery(queryId: String) -> Query? {
        guard let query = mQueries.removeValue(forKey: queryId) else {
            return nil
        }
        
        if let token = mQueryListenerTokens.removeValue(forKey: queryId) {
            query.removeChangeListener(withToken: token)
        }
        
        return query
    }
    
    func setReplicatorEndpoint(endpoint: String) {
        let targetEndpoint = URLEndpoint(url: URL(string: endpoint)!)
        mReplConfig = ReplicatorConfiguration(database: getDatabase()!, target: targetEndpoint)
    }
    
    func setReplicatorType(type: String) -> String {
        var settedType: ReplicatorType = ReplicatorType.pull
        if (type == "PUSH") {
            settedType = .push
        } else if (type == "PULL") {
            settedType = .pull
        } else if (type == "PUSH_AND_PULL") {
            settedType = .pushAndPull
        }
        mReplConfig?.replicatorType = settedType
        switch(mReplConfig?.replicatorType.rawValue) {
        case (0):
            return "PUSH_AND_PULL"
        case (1):
            return "PUSH"
        case(2):
            return "PULL"
        default:
            return ""
        }
    }
    
    func setReplicatorAuthentication(auth: [String:String]) {
        if let username = auth["username"], let password = auth["password"] {
            mReplConfig?.authenticator = BasicAuthenticator(username: username, password: password)
        }
    }
    
    func setReplicatorSessionAuthentication(sessionID: String?) {
        guard let _sessionID = sessionID, let _mReplConfig = mReplConfig else {
            return;
        }
        
        _mReplConfig.authenticator = SessionAuthenticator(sessionID: _sessionID)
    }
    
    func setReplicatorPinnedServerCertificate(assetKey: String) throws {
        guard let delegate = mDelegate, let _mReplConfig = mReplConfig else {
            return
        }
        
        let key = delegate.lookupKey(forAsset: assetKey)
        
        if let path = Bundle.main.path(forResource: key, ofType: nil), let data = NSData(contentsOfFile: path) {
            _mReplConfig.pinnedServerCertificate = SecCertificateCreateWithData(nil,data)
        } else {
            throw CBManagerError.CertificatePinning
        }
    }
    
    func setReplicatorContinuous(isContinuous: Bool) {
        if ((mReplConfig) != nil) {
            mReplConfig?.continuous = isContinuous
        }
    }
    
    func initReplicator() {
        guard let _mReplConfig = mReplConfig else {
            return;
        }
        
        mReplicator = Replicator(config: _mReplConfig)
    }
    
    func startReplication() {
        guard let _mReplicator = mReplicator else {
            return;
        }
        
        _mReplicator.start()
    }
    
    func stopReplication() {
        guard let _mReplicator = mReplicator else {
            return;
        }
        
        _mReplicator.stop()
    }
    
    func getReplicator() -> Replicator? {
        return mReplicator
    }
}
