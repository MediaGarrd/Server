package wellatleastitried.mediagarrdServer.repository;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;

public final class TestDatabase {

    public final SqliteDatabase database;
    public final BackupRecordRepository backups;
    public final ServiceRepository services;
    public final SettingsRepository settings;
    public final PairedClientRepository pairedClients;

    public TestDatabase(MediaGarrdProperties properties) {
        this(new SqliteDatabase(properties));
    }

    public TestDatabase(SqliteDatabase database) {
        this.database = database;
        this.backups = new BackupRecordRepository(database);
        this.services = new ServiceRepository(database);
        this.settings = new SettingsRepository(database);
        this.pairedClients = new PairedClientRepository(database);
    }
}
