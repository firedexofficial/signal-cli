package org.asamk.signal.manager.storage.recipients;

import org.asamk.signal.manager.api.Contact;
import org.asamk.signal.manager.api.Pair;
import org.asamk.signal.manager.api.Profile;
import org.asamk.signal.manager.api.UnregisteredRecipientException;
import org.asamk.signal.manager.storage.Database;
import org.asamk.signal.manager.storage.Utils;
import org.asamk.signal.manager.storage.contacts.ContactsStore;
import org.asamk.signal.manager.storage.profiles.ProfileStore;
import org.asamk.signal.manager.util.KeyUtils;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.profiles.ExpiringProfileKeyCredential;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.ServiceId.ACI;
import org.whispersystems.signalservice.api.push.ServiceId.PNI;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.storage.StorageId;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class RecipientStore implements RecipientIdCreator, RecipientResolver, RecipientTrustedResolver, ContactsStore, ProfileStore {

    private static final Logger logger = LoggerFactory.getLogger(RecipientStore.class);
    private static final String TABLE_RECIPIENT = "recipient";
    private static final String SQL_IS_CONTACT = "r.given_name IS NOT NULL OR r.family_name IS NOT NULL OR r.nick_name IS NOT NULL OR r.expiration_time > 0 OR r.profile_sharing = TRUE OR r.color IS NOT NULL OR r.blocked = TRUE OR r.archived = TRUE";

    private final RecipientMergeHandler recipientMergeHandler;
    private final SelfAddressProvider selfAddressProvider;
    private final SelfProfileKeyProvider selfProfileKeyProvider;
    private final Database database;

    private final Object recipientsLock = new Object();
    private final Map<Long, Long> recipientsMerged = new HashMap<>();

    private final Map<ServiceId, RecipientWithAddress> recipientAddressCache = new HashMap<>();

    public static void createSql(Connection connection) throws SQLException {
        // When modifying the CREATE statement here, also add a migration in AccountDatabase.java
        try (final var statement = connection.createStatement()) {
            statement.executeUpdate("""
                                    CREATE TABLE recipient (
                                      _id INTEGER PRIMARY KEY AUTOINCREMENT,
                                      storage_id BLOB UNIQUE,
                                      storage_record BLOB,
                                      number TEXT UNIQUE,
                                      username TEXT UNIQUE,
                                      aci TEXT UNIQUE,
                                      pni TEXT UNIQUE,
                                      unregistered_timestamp INTEGER,
                                      profile_key BLOB,
                                      profile_key_credential BLOB,

                                      given_name TEXT,
                                      family_name TEXT,
                                      nick_name TEXT,
                                      color TEXT,

                                      expiration_time INTEGER NOT NULL DEFAULT 0,
                                      mute_until INTEGER NOT NULL DEFAULT 0,
                                      blocked INTEGER NOT NULL DEFAULT FALSE,
                                      archived INTEGER NOT NULL DEFAULT FALSE,
                                      profile_sharing INTEGER NOT NULL DEFAULT FALSE,
                                      hide_story INTEGER NOT NULL DEFAULT FALSE,
                                      hidden INTEGER NOT NULL DEFAULT FALSE,

                                      profile_last_update_timestamp INTEGER NOT NULL DEFAULT 0,
                                      profile_given_name TEXT,
                                      profile_family_name TEXT,
                                      profile_about TEXT,
                                      profile_about_emoji TEXT,
                                      profile_avatar_url_path TEXT,
                                      profile_mobile_coin_address BLOB,
                                      profile_unidentified_access_mode TEXT,
                                      profile_capabilities TEXT
                                    ) STRICT;
                                    """);
        }
    }

    public RecipientStore(
            final RecipientMergeHandler recipientMergeHandler,
            final SelfAddressProvider selfAddressProvider,
            final SelfProfileKeyProvider selfProfileKeyProvider,
            final Database database
    ) {
        this.recipientMergeHandler = recipientMergeHandler;
        this.selfAddressProvider = selfAddressProvider;
        this.selfProfileKeyProvider = selfProfileKeyProvider;
        this.database = database;
    }

    public RecipientAddress resolveRecipientAddress(RecipientId recipientId) {
        try (final var connection = database.getConnection()) {
            return resolveRecipientAddress(connection, recipientId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
    }

    public Collection<RecipientId> getRecipientIdsWithEnabledProfileSharing() {
        final var sql = (
                """
                SELECT r._id
                FROM %s r
                WHERE r.blocked = FALSE AND r.profile_sharing = TRUE
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var connection = database.getConnection()) {
            try (final var statement = connection.prepareStatement(sql)) {
                try (var result = Utils.executeQueryForStream(statement, this::getRecipientIdFromResultSet)) {
                    return result.toList();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
    }

    @Override
    public RecipientId resolveRecipient(final long rawRecipientId) {
        final var sql = (
                """
                SELECT r._id
                FROM %s r
                WHERE r._id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var connection = database.getConnection()) {
            try (final var statement = connection.prepareStatement(sql)) {
                statement.setLong(1, rawRecipientId);
                return Utils.executeQueryForOptional(statement, this::getRecipientIdFromResultSet).orElse(null);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
    }

    @Override
    public RecipientId resolveRecipient(final String identifier) {
        final var serviceId = ServiceId.parseOrNull(identifier);
        if (serviceId != null) {
            return resolveRecipient(serviceId);
        } else {
            return resolveRecipientByNumber(identifier);
        }
    }

    private RecipientId resolveRecipientByNumber(final String number) {
        synchronized (recipientsLock) {
            final RecipientId recipientId;
            try (final var connection = database.getConnection()) {
                connection.setAutoCommit(false);
                recipientId = resolveRecipientLocked(connection, number);
                connection.commit();
            } catch (SQLException e) {
                throw new RuntimeException("Failed read recipient store", e);
            }
            return recipientId;
        }
    }

    @Override
    public RecipientId resolveRecipient(final ServiceId serviceId) {
        synchronized (recipientsLock) {
            final var recipientWithAddress = recipientAddressCache.get(serviceId);
            if (recipientWithAddress != null) {
                return recipientWithAddress.id();
            }
            try (final var connection = database.getConnection()) {
                connection.setAutoCommit(false);
                final var recipientId = resolveRecipientLocked(connection, serviceId);
                connection.commit();
                return recipientId;
            } catch (SQLException e) {
                throw new RuntimeException("Failed read recipient store", e);
            }
        }
    }

    /**
     * Should only be used for recipientIds from the database.
     * Where the foreign key relations ensure a valid recipientId.
     */
    @Override
    public RecipientId create(final long recipientId) {
        return new RecipientId(recipientId, this);
    }

    public RecipientId resolveRecipientByNumber(
            final String number, Supplier<ServiceId> serviceIdSupplier
    ) throws UnregisteredRecipientException {
        final Optional<RecipientWithAddress> byNumber;
        try (final var connection = database.getConnection()) {
            byNumber = findByNumber(connection, number);
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
        if (byNumber.isEmpty() || byNumber.get().address().serviceId().isEmpty()) {
            final var serviceId = serviceIdSupplier.get();
            if (serviceId == null) {
                throw new UnregisteredRecipientException(new org.asamk.signal.manager.api.RecipientAddress(null,
                        number));
            }

            return resolveRecipient(serviceId);
        }
        return byNumber.get().id();
    }

    public Optional<RecipientId> resolveRecipientByNumberOptional(final String number) {
        final Optional<RecipientWithAddress> byNumber;
        try (final var connection = database.getConnection()) {
            byNumber = findByNumber(connection, number);
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
        return byNumber.map(RecipientWithAddress::id);
    }

    public RecipientId resolveRecipientByUsername(
            final String username, Supplier<ACI> aciSupplier
    ) throws UnregisteredRecipientException {
        final Optional<RecipientWithAddress> byUsername;
        try (final var connection = database.getConnection()) {
            byUsername = findByUsername(connection, username);
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
        if (byUsername.isEmpty() || byUsername.get().address().serviceId().isEmpty()) {
            final var aci = aciSupplier.get();
            if (aci == null) {
                throw new UnregisteredRecipientException(new org.asamk.signal.manager.api.RecipientAddress(null,
                        null,
                        username));
            }

            return resolveRecipientTrusted(aci, username);
        }
        return byUsername.get().id();
    }

    public RecipientId resolveRecipient(RecipientAddress address) {
        synchronized (recipientsLock) {
            final RecipientId recipientId;
            try (final var connection = database.getConnection()) {
                connection.setAutoCommit(false);
                recipientId = resolveRecipientLocked(connection, address);
                connection.commit();
            } catch (SQLException e) {
                throw new RuntimeException("Failed read recipient store", e);
            }
            return recipientId;
        }
    }

    public RecipientId resolveRecipient(Connection connection, RecipientAddress address) throws SQLException {
        return resolveRecipientLocked(connection, address);
    }

    @Override
    public RecipientId resolveSelfRecipientTrusted(RecipientAddress address) {
        return resolveRecipientTrusted(address, true);
    }

    @Override
    public RecipientId resolveRecipientTrusted(RecipientAddress address) {
        return resolveRecipientTrusted(address, false);
    }

    public RecipientId resolveRecipientTrusted(Connection connection, RecipientAddress address) throws SQLException {
        final var pair = resolveRecipientTrustedLocked(connection, address, false);
        if (!pair.second().isEmpty()) {
            mergeRecipients(connection, pair.first(), pair.second());
        }
        return pair.first();
    }

    @Override
    public RecipientId resolveRecipientTrusted(SignalServiceAddress address) {
        return resolveRecipientTrusted(new RecipientAddress(address));
    }

    @Override
    public RecipientId resolveRecipientTrusted(
            final Optional<ACI> aci, final Optional<PNI> pni, final Optional<String> number
    ) {
        return resolveRecipientTrusted(new RecipientAddress(aci, pni, number, Optional.empty()));
    }

    @Override
    public RecipientId resolveRecipientTrusted(final ACI aci, final String username) {
        return resolveRecipientTrusted(new RecipientAddress(aci, null, null, username));
    }

    @Override
    public void storeContact(RecipientId recipientId, final Contact contact) {
        try (final var connection = database.getConnection()) {
            storeContact(connection, recipientId, contact);
        } catch (SQLException e) {
            throw new RuntimeException("Failed update recipient store", e);
        }
    }

    @Override
    public Contact getContact(RecipientId recipientId) {
        try (final var connection = database.getConnection()) {
            return getContact(connection, recipientId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
    }

    @Override
    public List<Pair<RecipientId, Contact>> getContacts() {
        final var sql = (
                """
                SELECT r._id, r.given_name, r.family_name, r.nick_name, r.expiration_time, r.mute_until, r.hide_story, r.profile_sharing, r.color, r.blocked, r.archived, r.hidden, r.unregistered_timestamp
                FROM %s r
                WHERE (r.number IS NOT NULL OR r.aci IS NOT NULL) AND %s AND r.hidden = FALSE
                """
        ).formatted(TABLE_RECIPIENT, SQL_IS_CONTACT);
        try (final var connection = database.getConnection()) {
            try (final var statement = connection.prepareStatement(sql)) {
                try (var result = Utils.executeQueryForStream(statement,
                        resultSet -> new Pair<>(getRecipientIdFromResultSet(resultSet),
                                getContactFromResultSet(resultSet)))) {
                    return result.toList();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
    }

    public Recipient getRecipient(Connection connection, RecipientId recipientId) throws SQLException {
        final var sql = (
                """
                SELECT r._id,
                       r.number, r.aci, r.pni, r.username,
                       r.profile_key, r.profile_key_credential,
                       r.given_name, r.family_name, r.nick_name, r.expiration_time, r.mute_until, r.hide_story, r.profile_sharing, r.color, r.blocked, r.archived, r.hidden, r.unregistered_timestamp,
                       r.profile_last_update_timestamp, r.profile_given_name, r.profile_family_name, r.profile_about, r.profile_about_emoji, r.profile_avatar_url_path, r.profile_mobile_coin_address, r.profile_unidentified_access_mode, r.profile_capabilities,
                       r.storage_record
                FROM %s r
                WHERE r._id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientId.id());
            return Utils.executeQuerySingleRow(statement, this::getRecipientFromResultSet);
        }
    }

    public Recipient getRecipient(Connection connection, StorageId storageId) throws SQLException {
        final var sql = (
                """
                SELECT r._id,
                       r.number, r.aci, r.pni, r.username,
                       r.profile_key, r.profile_key_credential,
                       r.given_name, r.family_name, r.nick_name, r.expiration_time, r.mute_until, r.hide_story, r.profile_sharing, r.color, r.blocked, r.archived, r.hidden, r.unregistered_timestamp,
                       r.profile_last_update_timestamp, r.profile_given_name, r.profile_family_name, r.profile_about, r.profile_about_emoji, r.profile_avatar_url_path, r.profile_mobile_coin_address, r.profile_unidentified_access_mode, r.profile_capabilities,
                       r.storage_record
                FROM %s r
                WHERE r.storage_id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, storageId.getRaw());
            return Utils.executeQuerySingleRow(statement, this::getRecipientFromResultSet);
        }
    }

    public List<Recipient> getRecipients(
            boolean onlyContacts, Optional<Boolean> blocked, Set<RecipientId> recipientIds, Optional<String> name
    ) {
        final var sqlWhere = new ArrayList<String>();
        if (onlyContacts) {
            sqlWhere.add("r.unregistered_timestamp IS NULL");
            sqlWhere.add("(" + SQL_IS_CONTACT + ")");
            sqlWhere.add("r.hidden = FALSE");
        }
        if (blocked.isPresent()) {
            sqlWhere.add("r.blocked = ?");
        }
        if (!recipientIds.isEmpty()) {
            final var recipientIdsCommaSeparated = recipientIds.stream()
                    .map(recipientId -> String.valueOf(recipientId.id()))
                    .collect(Collectors.joining(","));
            sqlWhere.add("r._id IN (" + recipientIdsCommaSeparated + ")");
        }
        final var sql = (
                """
                SELECT r._id,
                       r.number, r.aci, r.pni, r.username,
                       r.profile_key, r.profile_key_credential,
                       r.given_name, r.family_name, r.nick_name, r.expiration_time, r.mute_until, r.hide_story, r.profile_sharing, r.color, r.blocked, r.archived, r.hidden, r.unregistered_timestamp,
                       r.profile_last_update_timestamp, r.profile_given_name, r.profile_family_name, r.profile_about, r.profile_about_emoji, r.profile_avatar_url_path, r.profile_mobile_coin_address, r.profile_unidentified_access_mode, r.profile_capabilities,
                       r.storage_record
                FROM %s r
                WHERE (r.number IS NOT NULL OR r.aci IS NOT NULL) AND %s
                """
        ).formatted(TABLE_RECIPIENT, sqlWhere.isEmpty() ? "TRUE" : String.join(" AND ", sqlWhere));
        final var selfAddress = selfAddressProvider.getSelfAddress();
        try (final var connection = database.getConnection()) {
            try (final var statement = connection.prepareStatement(sql)) {
                if (blocked.isPresent()) {
                    statement.setBoolean(1, blocked.get());
                }
                try (var result = Utils.executeQueryForStream(statement, this::getRecipientFromResultSet)) {
                    return result.filter(r -> name.isEmpty() || (
                            r.getContact() != null && name.get().equals(r.getContact().getName())
                    ) || (r.getProfile() != null && name.get().equals(r.getProfile().getDisplayName()))).map(r -> {
                        if (r.getAddress().matches(selfAddress)) {
                            return Recipient.newBuilder(r)
                                    .withProfileKey(selfProfileKeyProvider.getSelfProfileKey())
                                    .build();
                        }
                        return r;
                    }).toList();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
    }

    public Set<String> getAllNumbers() {
        final var sql = (
                """
                SELECT r.number
                FROM %s r
                WHERE r.number IS NOT NULL
                """
        ).formatted(TABLE_RECIPIENT);
        final var selfNumber = selfAddressProvider.getSelfAddress().number().orElse(null);
        try (final var connection = database.getConnection()) {
            try (final var statement = connection.prepareStatement(sql)) {
                return Utils.executeQueryForStream(statement, resultSet -> resultSet.getString("number"))
                        .filter(Objects::nonNull)
                        .filter(n -> !n.equals(selfNumber))
                        .filter(n -> {
                            try {
                                Long.parseLong(n);
                                return true;
                            } catch (NumberFormatException e) {
                                return false;
                            }
                        })
                        .collect(Collectors.toSet());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
    }

    public Map<ServiceId, ProfileKey> getServiceIdToProfileKeyMap() {
        final var sql = (
                """
                SELECT r.aci, r.profile_key
                FROM %s r
                WHERE r.aci IS NOT NULL AND r.profile_key IS NOT NULL
                """
        ).formatted(TABLE_RECIPIENT);
        final var selfAci = selfAddressProvider.getSelfAddress().aci().orElse(null);
        try (final var connection = database.getConnection()) {
            try (final var statement = connection.prepareStatement(sql)) {
                return Utils.executeQueryForStream(statement, resultSet -> {
                    final var aci = ACI.parseOrThrow(resultSet.getString("aci"));
                    if (aci.equals(selfAci)) {
                        return new Pair<>(aci, selfProfileKeyProvider.getSelfProfileKey());
                    }
                    final var profileKey = getProfileKeyFromResultSet(resultSet);
                    return new Pair<>(aci, profileKey);
                }).filter(Objects::nonNull).collect(Collectors.toMap(Pair::first, Pair::second));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
    }

    public List<RecipientId> getRecipientIds(Connection connection) throws SQLException {
        final var sql = (
                """
                SELECT r._id
                FROM %s r
                WHERE (r.number IS NOT NULL OR r.aci IS NOT NULL)
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            return Utils.executeQueryForStream(statement, this::getRecipientIdFromResultSet).toList();
        }
    }

    public void setMissingStorageIds() {
        final var selectSql = (
                """
                SELECT r._id
                FROM %s r
                WHERE r.storage_id IS NULL AND r.unregistered_timestamp IS NULL
                """
        ).formatted(TABLE_RECIPIENT);
        final var updateSql = (
                """
                UPDATE %s
                SET storage_id = ?
                WHERE _id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (final var selectStmt = connection.prepareStatement(selectSql)) {
                final var recipientIds = Utils.executeQueryForStream(selectStmt, this::getRecipientIdFromResultSet)
                        .toList();
                try (final var updateStmt = connection.prepareStatement(updateSql)) {
                    for (final var recipientId : recipientIds) {
                        updateStmt.setBytes(1, KeyUtils.createRawStorageId());
                        updateStmt.setLong(2, recipientId.id());
                        updateStmt.executeUpdate();
                    }
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed update recipient store", e);
        }
    }

    @Override
    public void deleteContact(RecipientId recipientId) {
        storeContact(recipientId, null);
    }

    public void deleteRecipientData(RecipientId recipientId) {
        logger.debug("Deleting recipient data for {}", recipientId);
        synchronized (recipientsLock) {
            recipientAddressCache.entrySet().removeIf(e -> e.getValue().id().equals(recipientId));
            try (final var connection = database.getConnection()) {
                connection.setAutoCommit(false);
                storeContact(connection, recipientId, null);
                storeProfile(connection, recipientId, null);
                storeProfileKey(connection, recipientId, null, false);
                storeExpiringProfileKeyCredential(connection, recipientId, null);
                deleteRecipient(connection, recipientId);
                connection.commit();
            } catch (SQLException e) {
                throw new RuntimeException("Failed update recipient store", e);
            }
        }
    }

    @Override
    public Profile getProfile(final RecipientId recipientId) {
        try (final var connection = database.getConnection()) {
            return getProfile(connection, recipientId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
    }

    @Override
    public ProfileKey getProfileKey(final RecipientId recipientId) {
        try (final var connection = database.getConnection()) {
            return getProfileKey(connection, recipientId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
    }

    @Override
    public ExpiringProfileKeyCredential getExpiringProfileKeyCredential(final RecipientId recipientId) {
        try (final var connection = database.getConnection()) {
            return getExpiringProfileKeyCredential(connection, recipientId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed read from recipient store", e);
        }
    }

    @Override
    public void storeProfile(RecipientId recipientId, final Profile profile) {
        try (final var connection = database.getConnection()) {
            storeProfile(connection, recipientId, profile);
        } catch (SQLException e) {
            throw new RuntimeException("Failed update recipient store", e);
        }
    }

    @Override
    public void storeProfileKey(RecipientId recipientId, final ProfileKey profileKey) {
        try (final var connection = database.getConnection()) {
            storeProfileKey(connection, recipientId, profileKey);
        } catch (SQLException e) {
            throw new RuntimeException("Failed update recipient store", e);
        }
    }

    public void storeProfileKey(
            Connection connection, RecipientId recipientId, final ProfileKey profileKey
    ) throws SQLException {
        storeProfileKey(connection, recipientId, profileKey, true);
    }

    @Override
    public void storeExpiringProfileKeyCredential(
            RecipientId recipientId, final ExpiringProfileKeyCredential profileKeyCredential
    ) {
        try (final var connection = database.getConnection()) {
            storeExpiringProfileKeyCredential(connection, recipientId, profileKeyCredential);
        } catch (SQLException e) {
            throw new RuntimeException("Failed update recipient store", e);
        }
    }

    public void rotateSelfStorageId() {
        try (final var connection = database.getConnection()) {
            rotateSelfStorageId(connection);
        } catch (SQLException e) {
            throw new RuntimeException("Failed update recipient store", e);
        }
    }

    public void rotateSelfStorageId(final Connection connection) throws SQLException {
        final var selfRecipientId = resolveRecipient(connection, selfAddressProvider.getSelfAddress());
        rotateStorageId(connection, selfRecipientId);
    }

    public StorageId rotateStorageId(final Connection connection, final ServiceId serviceId) throws SQLException {
        final var selfRecipientId = resolveRecipient(connection, new RecipientAddress(serviceId));
        return rotateStorageId(connection, selfRecipientId);
    }

    public List<StorageId> getStorageIds(Connection connection) throws SQLException {
        final var sql = """
                        SELECT r.storage_id
                        FROM %s r WHERE r.storage_id IS NOT NULL AND r._id != ? AND (r.aci IS NOT NULL OR r.pni IS NOT NULL)
                        """.formatted(TABLE_RECIPIENT);
        final var selfRecipientId = resolveRecipient(connection, selfAddressProvider.getSelfAddress());
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, selfRecipientId.id());
            return Utils.executeQueryForStream(statement, this::getContactStorageIdFromResultSet).toList();
        }
    }

    public void updateStorageId(
            Connection connection, RecipientId recipientId, StorageId storageId
    ) throws SQLException {
        final var sql = (
                """
                UPDATE %s
                SET storage_id = ?
                WHERE _id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, storageId.getRaw());
            statement.setLong(2, recipientId.id());
            statement.executeUpdate();
        }
    }

    public void updateStorageIds(Connection connection, Map<RecipientId, StorageId> storageIdMap) throws SQLException {
        final var sql = (
                """
                UPDATE %s
                SET storage_id = ?
                WHERE _id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            for (final var entry : storageIdMap.entrySet()) {
                statement.setBytes(1, entry.getValue().getRaw());
                statement.setLong(2, entry.getKey().id());
                statement.executeUpdate();
            }
        }
    }

    public StorageId getSelfStorageId(final Connection connection) throws SQLException {
        final var selfRecipientId = resolveRecipient(connection, selfAddressProvider.getSelfAddress());
        return StorageId.forAccount(getStorageId(connection, selfRecipientId).getRaw());
    }

    public StorageId getStorageId(final Connection connection, final RecipientId recipientId) throws SQLException {
        final var sql = """
                        SELECT r.storage_id
                        FROM %s r WHERE r._id = ? AND r.storage_id IS NOT NULL
                        """.formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientId.id());
            final var storageId = Utils.executeQueryForOptional(statement, this::getContactStorageIdFromResultSet);
            if (storageId.isPresent()) {
                return storageId.get();
            }
        }
        return rotateStorageId(connection, recipientId);
    }

    private StorageId rotateStorageId(final Connection connection, final RecipientId recipientId) throws SQLException {
        final var newStorageId = StorageId.forAccount(KeyUtils.createRawStorageId());
        updateStorageId(connection, recipientId, newStorageId);
        return newStorageId;
    }

    public void storeStorageRecord(
            final Connection connection,
            final RecipientId recipientId,
            final StorageId storageId,
            final byte[] storageRecord
    ) throws SQLException {
        final var deleteSql = (
                """
                UPDATE %s
                SET storage_id = NULL
                WHERE storage_id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(deleteSql)) {
            statement.setBytes(1, storageId.getRaw());
            statement.executeUpdate();
        }
        final var insertSql = (
                """
                UPDATE %s
                SET storage_id = ?, storage_record = ?
                WHERE _id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(insertSql)) {
            statement.setBytes(1, storageId.getRaw());
            if (storageRecord == null) {
                statement.setNull(2, Types.BLOB);
            } else {
                statement.setBytes(2, storageRecord);
            }
            statement.setLong(3, recipientId.id());
            statement.executeUpdate();
        }
    }

    void addLegacyRecipients(final Map<RecipientId, Recipient> recipients) {
        logger.debug("Migrating legacy recipients to database");
        long start = System.nanoTime();
        final var sql = (
                """
                INSERT INTO %s (_id, number, aci)
                VALUES (?, ?, ?)
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (final var statement = connection.prepareStatement("DELETE FROM %s".formatted(TABLE_RECIPIENT))) {
                statement.executeUpdate();
            }
            try (final var statement = connection.prepareStatement(sql)) {
                for (final var recipient : recipients.values()) {
                    statement.setLong(1, recipient.getRecipientId().id());
                    statement.setString(2, recipient.getAddress().number().orElse(null));
                    statement.setString(3, recipient.getAddress().aci().map(ACI::toString).orElse(null));
                    statement.executeUpdate();
                }
            }
            logger.debug("Initial inserts took {}ms", (System.nanoTime() - start) / 1000000);

            for (final var recipient : recipients.values()) {
                if (recipient.getContact() != null) {
                    storeContact(connection, recipient.getRecipientId(), recipient.getContact());
                }
                if (recipient.getProfile() != null) {
                    storeProfile(connection, recipient.getRecipientId(), recipient.getProfile());
                }
                if (recipient.getProfileKey() != null) {
                    storeProfileKey(connection, recipient.getRecipientId(), recipient.getProfileKey(), false);
                }
                if (recipient.getExpiringProfileKeyCredential() != null) {
                    storeExpiringProfileKeyCredential(connection,
                            recipient.getRecipientId(),
                            recipient.getExpiringProfileKeyCredential());
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed update recipient store", e);
        }
        logger.debug("Complete recipients migration took {}ms", (System.nanoTime() - start) / 1000000);
    }

    long getActualRecipientId(long recipientId) {
        while (recipientsMerged.containsKey(recipientId)) {
            final var newRecipientId = recipientsMerged.get(recipientId);
            logger.debug("Using {} instead of {}, because recipients have been merged", newRecipientId, recipientId);
            recipientId = newRecipientId;
        }
        return recipientId;
    }

    public void storeContact(
            final Connection connection, final RecipientId recipientId, final Contact contact
    ) throws SQLException {
        final var sql = (
                """
                UPDATE %s
                SET given_name = ?, family_name = ?, nick_name = ?, expiration_time = ?, mute_until = ?, hide_story = ?, profile_sharing = ?, color = ?, blocked = ?, archived = ?, unregistered_timestamp = ?
                WHERE _id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setString(1, contact == null ? null : contact.givenName());
            statement.setString(2, contact == null ? null : contact.familyName());
            statement.setString(3, contact == null ? null : contact.nickName());
            statement.setInt(4, contact == null ? 0 : contact.messageExpirationTime());
            statement.setLong(5, contact == null ? 0 : contact.muteUntil());
            statement.setBoolean(6, contact != null && contact.hideStory());
            statement.setBoolean(7, contact != null && contact.isProfileSharingEnabled());
            statement.setString(8, contact == null ? null : contact.color());
            statement.setBoolean(9, contact != null && contact.isBlocked());
            statement.setBoolean(10, contact != null && contact.isArchived());
            if (contact == null || contact.unregisteredTimestamp() == null) {
                statement.setNull(11, Types.INTEGER);
            } else {
                statement.setLong(11, contact.unregisteredTimestamp());
            }
            statement.setLong(12, recipientId.id());
            statement.executeUpdate();
        }
        if (contact != null && contact.unregisteredTimestamp() != null) {
            markUnregisteredAndSplitIfNecessary(connection, recipientId);
        }
        rotateStorageId(connection, recipientId);
    }

    public int removeStorageIdsFromLocalOnlyUnregisteredRecipients(
            final Connection connection, final List<StorageId> storageIds
    ) throws SQLException {
        final var sql = (
                """
                UPDATE %s
                SET storage_id = NULL
                WHERE storage_id = ? AND unregistered_timestamp IS NOT NULL
                """
        ).formatted(TABLE_RECIPIENT);
        var count = 0;
        try (final var statement = connection.prepareStatement(sql)) {
            for (final var storageId : storageIds) {
                statement.setBytes(1, storageId.getRaw());
                count += statement.executeUpdate();
            }
        }
        return count;
    }

    public void markUnregistered(final Set<String> unregisteredUsers) {
        logger.debug("Marking {} numbers as unregistered", unregisteredUsers.size());
        try (final var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            for (final var number : unregisteredUsers) {
                final var recipient = findByNumber(connection, number);
                if (recipient.isPresent()) {
                    final var recipientId = recipient.get().id();
                    markUnregisteredAndSplitIfNecessary(connection, recipientId);
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed update recipient store", e);
        }
    }

    private void markUnregisteredAndSplitIfNecessary(
            final Connection connection, final RecipientId recipientId
    ) throws SQLException {
        markUnregistered(connection, recipientId);
        final var address = resolveRecipientAddress(connection, recipientId);
        if (address.aci().isPresent() && address.pni().isPresent()) {
            final var numberAddress = new RecipientAddress(address.pni().get(), address.number().orElse(null));
            updateRecipientAddress(connection, recipientId, address.removeIdentifiersFrom(numberAddress));
            addNewRecipient(connection, numberAddress);
        }
    }

    private void markRegistered(
            final Connection connection, final RecipientId recipientId
    ) throws SQLException {
        final var sql = (
                """
                UPDATE %s
                SET unregistered_timestamp = ?
                WHERE _id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setNull(1, Types.INTEGER);
            statement.setLong(2, recipientId.id());
            statement.executeUpdate();
        }
    }

    private void markUnregistered(
            final Connection connection, final RecipientId recipientId
    ) throws SQLException {
        final var sql = (
                """
                UPDATE %s
                SET unregistered_timestamp = ?
                WHERE _id = ? AND unregistered_timestamp IS NULL
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setLong(2, recipientId.id());
            statement.executeUpdate();
        }
    }

    private void storeExpiringProfileKeyCredential(
            final Connection connection,
            final RecipientId recipientId,
            final ExpiringProfileKeyCredential profileKeyCredential
    ) throws SQLException {
        final var sql = (
                """
                UPDATE %s
                SET profile_key_credential = ?
                WHERE _id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, profileKeyCredential == null ? null : profileKeyCredential.serialize());
            statement.setLong(2, recipientId.id());
            statement.executeUpdate();
        }
    }

    public void storeProfile(
            final Connection connection, final RecipientId recipientId, final Profile profile
    ) throws SQLException {
        final var sql = (
                """
                UPDATE %s
                SET profile_last_update_timestamp = ?, profile_given_name = ?, profile_family_name = ?, profile_about = ?, profile_about_emoji = ?, profile_avatar_url_path = ?, profile_mobile_coin_address = ?, profile_unidentified_access_mode = ?, profile_capabilities = ?
                WHERE _id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, profile == null ? 0 : profile.getLastUpdateTimestamp());
            statement.setString(2, profile == null ? null : profile.getGivenName());
            statement.setString(3, profile == null ? null : profile.getFamilyName());
            statement.setString(4, profile == null ? null : profile.getAbout());
            statement.setString(5, profile == null ? null : profile.getAboutEmoji());
            statement.setString(6, profile == null ? null : profile.getAvatarUrlPath());
            statement.setBytes(7, profile == null ? null : profile.getMobileCoinAddress());
            statement.setString(8, profile == null ? null : profile.getUnidentifiedAccessMode().name());
            statement.setString(9,
                    profile == null
                            ? null
                            : profile.getCapabilities().stream().map(Enum::name).collect(Collectors.joining(",")));
            statement.setLong(10, recipientId.id());
            statement.executeUpdate();
        }
        rotateStorageId(connection, recipientId);
    }

    private void storeProfileKey(
            Connection connection, RecipientId recipientId, final ProfileKey profileKey, boolean resetProfile
    ) throws SQLException {
        if (profileKey != null) {
            final var recipientProfileKey = getProfileKey(connection, recipientId);
            if (profileKey.equals(recipientProfileKey)) {
                final var recipientProfile = getProfile(connection, recipientId);
                if (recipientProfile == null || (
                        recipientProfile.getUnidentifiedAccessMode() != Profile.UnidentifiedAccessMode.UNKNOWN
                                && recipientProfile.getUnidentifiedAccessMode()
                                != Profile.UnidentifiedAccessMode.DISABLED
                )) {
                    return;
                }
            }
        }

        final var sql = (
                """
                UPDATE %s
                SET profile_key = ?, profile_key_credential = NULL%s
                WHERE _id = ?
                """
        ).formatted(TABLE_RECIPIENT, resetProfile ? ", profile_last_update_timestamp = 0" : "");
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, profileKey == null ? null : profileKey.serialize());
            statement.setLong(2, recipientId.id());
            statement.executeUpdate();
        }
        rotateStorageId(connection, recipientId);
    }

    private RecipientAddress resolveRecipientAddress(
            final Connection connection, final RecipientId recipientId
    ) throws SQLException {
        final var sql = (
                """
                SELECT r.number, r.aci, r.pni, r.username
                FROM %s r
                WHERE r._id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientId.id());
            return Utils.executeQuerySingleRow(statement, this::getRecipientAddressFromResultSet);
        }
    }

    private RecipientId resolveRecipientTrusted(RecipientAddress address, boolean isSelf) {
        final Pair<RecipientId, List<RecipientId>> pair;
        synchronized (recipientsLock) {
            try (final var connection = database.getConnection()) {
                connection.setAutoCommit(false);
                pair = resolveRecipientTrustedLocked(connection, address, isSelf);
                connection.commit();
            } catch (SQLException e) {
                throw new RuntimeException("Failed update recipient store", e);
            }
        }

        if (!pair.second().isEmpty()) {
            logger.debug("Resolved address {}, merging {} other recipients", address, pair.second().size());
            try (final var connection = database.getConnection()) {
                connection.setAutoCommit(false);
                mergeRecipients(connection, pair.first(), pair.second());
                connection.commit();
            } catch (SQLException e) {
                throw new RuntimeException("Failed update recipient store", e);
            }
        }
        return pair.first();
    }

    private Pair<RecipientId, List<RecipientId>> resolveRecipientTrustedLocked(
            final Connection connection, final RecipientAddress address, final boolean isSelf
    ) throws SQLException {
        if (address.hasSingleIdentifier() || (
                !isSelf && selfAddressProvider.getSelfAddress().matches(address)
        )) {
            return new Pair<>(resolveRecipientLocked(connection, address), List.of());
        } else {
            final var pair = MergeRecipientHelper.resolveRecipientTrustedLocked(new HelperStore(connection), address);
            markRegistered(connection, pair.first());

            for (final var toBeMergedRecipientId : pair.second()) {
                mergeRecipientsLocked(connection, pair.first(), toBeMergedRecipientId);
            }
            return pair;
        }
    }

    private void mergeRecipients(
            final Connection connection, final RecipientId recipientId, final List<RecipientId> toBeMergedRecipientIds
    ) throws SQLException {
        for (final var toBeMergedRecipientId : toBeMergedRecipientIds) {
            recipientMergeHandler.mergeRecipients(connection, recipientId, toBeMergedRecipientId);
            deleteRecipient(connection, toBeMergedRecipientId);
            synchronized (recipientsLock) {
                recipientAddressCache.entrySet().removeIf(e -> e.getValue().id().equals(toBeMergedRecipientId));
            }
        }
    }

    private RecipientId resolveRecipientLocked(
            Connection connection, RecipientAddress address
    ) throws SQLException {
        final var byAci = address.aci().isEmpty()
                ? Optional.<RecipientWithAddress>empty()
                : findByServiceId(connection, address.aci().get());

        if (byAci.isPresent()) {
            return byAci.get().id();
        }

        final var byPni = address.pni().isEmpty()
                ? Optional.<RecipientWithAddress>empty()
                : findByServiceId(connection, address.pni().get());

        if (byPni.isPresent()) {
            return byPni.get().id();
        }

        final var byNumber = address.number().isEmpty()
                ? Optional.<RecipientWithAddress>empty()
                : findByNumber(connection, address.number().get());

        if (byNumber.isPresent()) {
            return byNumber.get().id();
        }

        logger.debug("Got new recipient, both serviceId and number are unknown");

        if (address.serviceId().isEmpty()) {
            return addNewRecipient(connection, address);
        }

        return addNewRecipient(connection, new RecipientAddress(address.serviceId().get()));
    }

    private RecipientId resolveRecipientLocked(Connection connection, ServiceId serviceId) throws SQLException {
        final var recipient = findByServiceId(connection, serviceId);

        if (recipient.isEmpty()) {
            logger.debug("Got new recipient, serviceId is unknown");
            return addNewRecipient(connection, new RecipientAddress(serviceId));
        }

        return recipient.get().id();
    }

    private RecipientId resolveRecipientLocked(Connection connection, String number) throws SQLException {
        final var recipient = findByNumber(connection, number);

        if (recipient.isEmpty()) {
            logger.debug("Got new recipient, number is unknown");
            return addNewRecipient(connection, new RecipientAddress(number));
        }

        return recipient.get().id();
    }

    private RecipientId addNewRecipient(
            final Connection connection, final RecipientAddress address
    ) throws SQLException {
        final var sql = (
                """
                INSERT INTO %s (number, aci, pni, username)
                VALUES (?, ?, ?, ?)
                RETURNING _id
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setString(1, address.number().orElse(null));
            statement.setString(2, address.aci().map(ACI::toString).orElse(null));
            statement.setString(3, address.pni().map(PNI::toString).orElse(null));
            statement.setString(4, address.username().orElse(null));
            final var generatedKey = Utils.executeQueryForOptional(statement, Utils::getIdMapper);
            if (generatedKey.isPresent()) {
                final var recipientId = new RecipientId(generatedKey.get(), this);
                logger.debug("Added new recipient {} with address {}", recipientId, address);
                return recipientId;
            } else {
                throw new RuntimeException("Failed to add new recipient to database");
            }
        }
    }

    private void removeRecipientAddress(Connection connection, RecipientId recipientId) throws SQLException {
        synchronized (recipientsLock) {
            recipientAddressCache.entrySet().removeIf(e -> e.getValue().id().equals(recipientId));
            final var sql = (
                    """
                    UPDATE %s
                    SET number = NULL, aci = NULL, pni = NULL, username = NULL, storage_id = NULL
                    WHERE _id = ?
                    """
            ).formatted(TABLE_RECIPIENT);
            try (final var statement = connection.prepareStatement(sql)) {
                statement.setLong(1, recipientId.id());
                statement.executeUpdate();
            }
        }
    }

    private void updateRecipientAddress(
            Connection connection, RecipientId recipientId, final RecipientAddress address
    ) throws SQLException {
        synchronized (recipientsLock) {
            recipientAddressCache.entrySet().removeIf(e -> e.getValue().id().equals(recipientId));
            final var sql = (
                    """
                    UPDATE %s
                    SET number = ?, aci = ?, pni = ?, username = ?
                    WHERE _id = ?
                    """
            ).formatted(TABLE_RECIPIENT);
            try (final var statement = connection.prepareStatement(sql)) {
                statement.setString(1, address.number().orElse(null));
                statement.setString(2, address.aci().map(ACI::toString).orElse(null));
                statement.setString(3, address.pni().map(PNI::toString).orElse(null));
                statement.setString(4, address.username().orElse(null));
                statement.setLong(5, recipientId.id());
                statement.executeUpdate();
            }
            rotateStorageId(connection, recipientId);
        }
    }

    private void deleteRecipient(final Connection connection, final RecipientId recipientId) throws SQLException {
        final var sql = (
                """
                DELETE FROM %s
                WHERE _id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientId.id());
            statement.executeUpdate();
        }
    }

    private void mergeRecipientsLocked(
            Connection connection, RecipientId recipientId, RecipientId toBeMergedRecipientId
    ) throws SQLException {
        final var contact = getContact(connection, recipientId);
        if (contact == null) {
            final var toBeMergedContact = getContact(connection, toBeMergedRecipientId);
            storeContact(connection, recipientId, toBeMergedContact);
        }

        final var profileKey = getProfileKey(connection, recipientId);
        if (profileKey == null) {
            final var toBeMergedProfileKey = getProfileKey(connection, toBeMergedRecipientId);
            storeProfileKey(connection, recipientId, toBeMergedProfileKey, false);
        }

        final var profileKeyCredential = getExpiringProfileKeyCredential(connection, recipientId);
        if (profileKeyCredential == null) {
            final var toBeMergedProfileKeyCredential = getExpiringProfileKeyCredential(connection,
                    toBeMergedRecipientId);
            storeExpiringProfileKeyCredential(connection, recipientId, toBeMergedProfileKeyCredential);
        }

        final var profile = getProfile(connection, recipientId);
        if (profile == null) {
            final var toBeMergedProfile = getProfile(connection, toBeMergedRecipientId);
            storeProfile(connection, recipientId, toBeMergedProfile);
        }

        recipientsMerged.put(toBeMergedRecipientId.id(), recipientId.id());
    }

    private Optional<RecipientWithAddress> findByNumber(
            final Connection connection, final String number
    ) throws SQLException {
        final var sql = """
                        SELECT r._id, r.number, r.aci, r.pni, r.username
                        FROM %s r
                        WHERE r.number = ?
                        LIMIT 1
                        """.formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setString(1, number);
            return Utils.executeQueryForOptional(statement, this::getRecipientWithAddressFromResultSet);
        }
    }

    private Optional<RecipientWithAddress> findByUsername(
            final Connection connection, final String username
    ) throws SQLException {
        final var sql = """
                        SELECT r._id, r.number, r.aci, r.pni, r.username
                        FROM %s r
                        WHERE r.username = ?
                        LIMIT 1
                        """.formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            return Utils.executeQueryForOptional(statement, this::getRecipientWithAddressFromResultSet);
        }
    }

    private Optional<RecipientWithAddress> findByServiceId(
            final Connection connection, final ServiceId serviceId
    ) throws SQLException {
        var recipientWithAddress = Optional.ofNullable(recipientAddressCache.get(serviceId));
        if (recipientWithAddress.isPresent()) {
            return recipientWithAddress;
        }
        final var sql = """
                        SELECT r._id, r.number, r.aci, r.pni, r.username
                        FROM %s r
                        WHERE %s = ?1
                        LIMIT 1
                        """.formatted(TABLE_RECIPIENT, serviceId instanceof ACI ? "r.aci" : "r.pni");
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setString(1, serviceId.toString());
            recipientWithAddress = Utils.executeQueryForOptional(statement, this::getRecipientWithAddressFromResultSet);
            recipientWithAddress.ifPresent(r -> recipientAddressCache.put(serviceId, r));
            return recipientWithAddress;
        }
    }

    private Set<RecipientWithAddress> findAllByAddress(
            final Connection connection, final RecipientAddress address
    ) throws SQLException {
        final var sql = """
                        SELECT r._id, r.number, r.aci, r.pni, r.username
                        FROM %s r
                        WHERE r.aci = ?1 OR
                              r.pni = ?2 OR
                              r.number = ?3 OR
                              r.username = ?4
                        """.formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setString(1, address.aci().map(ServiceId::toString).orElse(null));
            statement.setString(2, address.pni().map(ServiceId::toString).orElse(null));
            statement.setString(3, address.number().orElse(null));
            statement.setString(4, address.username().orElse(null));
            return Utils.executeQueryForStream(statement, this::getRecipientWithAddressFromResultSet)
                    .collect(Collectors.toSet());
        }
    }

    private Contact getContact(final Connection connection, final RecipientId recipientId) throws SQLException {
        final var sql = (
                """
                SELECT r.given_name, r.family_name, r.nick_name, r.expiration_time, r.mute_until, r.hide_story, r.profile_sharing, r.color, r.blocked, r.archived, r.hidden, r.unregistered_timestamp
                FROM %s r
                WHERE r._id = ? AND (%s)
                """
        ).formatted(TABLE_RECIPIENT, SQL_IS_CONTACT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientId.id());
            return Utils.executeQueryForOptional(statement, this::getContactFromResultSet).orElse(null);
        }
    }

    private ProfileKey getProfileKey(final Connection connection, final RecipientId recipientId) throws SQLException {
        final var selfRecipientId = resolveRecipientLocked(connection, selfAddressProvider.getSelfAddress());
        if (recipientId.equals(selfRecipientId)) {
            return selfProfileKeyProvider.getSelfProfileKey();
        }
        final var sql = (
                """
                SELECT r.profile_key
                FROM %s r
                WHERE r._id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientId.id());
            return Utils.executeQueryForOptional(statement, this::getProfileKeyFromResultSet).orElse(null);
        }
    }

    private ExpiringProfileKeyCredential getExpiringProfileKeyCredential(
            final Connection connection, final RecipientId recipientId
    ) throws SQLException {
        final var sql = (
                """
                SELECT r.profile_key_credential
                FROM %s r
                WHERE r._id = ?
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientId.id());
            return Utils.executeQueryForOptional(statement, this::getExpiringProfileKeyCredentialFromResultSet)
                    .orElse(null);
        }
    }

    public Profile getProfile(final Connection connection, final RecipientId recipientId) throws SQLException {
        final var sql = (
                """
                SELECT r.profile_last_update_timestamp, r.profile_given_name, r.profile_family_name, r.profile_about, r.profile_about_emoji, r.profile_avatar_url_path, r.profile_mobile_coin_address, r.profile_unidentified_access_mode, r.profile_capabilities
                FROM %s r
                WHERE r._id = ? AND r.profile_capabilities IS NOT NULL
                """
        ).formatted(TABLE_RECIPIENT);
        try (final var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recipientId.id());
            return Utils.executeQueryForOptional(statement, this::getProfileFromResultSet).orElse(null);
        }
    }

    private RecipientAddress getRecipientAddressFromResultSet(ResultSet resultSet) throws SQLException {
        final var aci = Optional.ofNullable(resultSet.getString("aci")).map(ACI::parseOrThrow);
        final var pni = Optional.ofNullable(resultSet.getString("pni")).map(PNI::parseOrThrow);
        final var number = Optional.ofNullable(resultSet.getString("number"));
        final var username = Optional.ofNullable(resultSet.getString("username"));
        return new RecipientAddress(aci, pni, number, username);
    }

    private RecipientId getRecipientIdFromResultSet(ResultSet resultSet) throws SQLException {
        return new RecipientId(resultSet.getLong("_id"), this);
    }

    private RecipientWithAddress getRecipientWithAddressFromResultSet(final ResultSet resultSet) throws SQLException {
        return new RecipientWithAddress(getRecipientIdFromResultSet(resultSet),
                getRecipientAddressFromResultSet(resultSet));
    }

    private Recipient getRecipientFromResultSet(final ResultSet resultSet) throws SQLException {
        return new Recipient(getRecipientIdFromResultSet(resultSet),
                getRecipientAddressFromResultSet(resultSet),
                getContactFromResultSet(resultSet),
                getProfileKeyFromResultSet(resultSet),
                getExpiringProfileKeyCredentialFromResultSet(resultSet),
                getProfileFromResultSet(resultSet),
                getStorageRecordFromResultSet(resultSet));
    }

    private Contact getContactFromResultSet(ResultSet resultSet) throws SQLException {
        final var unregisteredTimestamp = resultSet.getLong("unregistered_timestamp");
        return new Contact(resultSet.getString("given_name"),
                resultSet.getString("family_name"),
                resultSet.getString("nick_name"),
                resultSet.getString("color"),
                resultSet.getInt("expiration_time"),
                resultSet.getLong("mute_until"),
                resultSet.getBoolean("hide_story"),
                resultSet.getBoolean("blocked"),
                resultSet.getBoolean("archived"),
                resultSet.getBoolean("profile_sharing"),
                resultSet.getBoolean("hidden"),
                unregisteredTimestamp == 0 ? null : unregisteredTimestamp);
    }

    private Profile getProfileFromResultSet(ResultSet resultSet) throws SQLException {
        final var profileCapabilities = resultSet.getString("profile_capabilities");
        final var profileUnidentifiedAccessMode = resultSet.getString("profile_unidentified_access_mode");
        return new Profile(resultSet.getLong("profile_last_update_timestamp"),
                resultSet.getString("profile_given_name"),
                resultSet.getString("profile_family_name"),
                resultSet.getString("profile_about"),
                resultSet.getString("profile_about_emoji"),
                resultSet.getString("profile_avatar_url_path"),
                resultSet.getBytes("profile_mobile_coin_address"),
                profileUnidentifiedAccessMode == null
                        ? Profile.UnidentifiedAccessMode.UNKNOWN
                        : Profile.UnidentifiedAccessMode.valueOfOrUnknown(profileUnidentifiedAccessMode),
                profileCapabilities == null
                        ? Set.of()
                        : Arrays.stream(profileCapabilities.split(","))
                                .map(Profile.Capability::valueOfOrNull)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet()));
    }

    private ProfileKey getProfileKeyFromResultSet(ResultSet resultSet) throws SQLException {
        final var profileKey = resultSet.getBytes("profile_key");

        if (profileKey == null) {
            return null;
        }
        try {
            return new ProfileKey(profileKey);
        } catch (InvalidInputException ignored) {
            return null;
        }
    }

    private ExpiringProfileKeyCredential getExpiringProfileKeyCredentialFromResultSet(ResultSet resultSet) throws SQLException {
        final var profileKeyCredential = resultSet.getBytes("profile_key_credential");

        if (profileKeyCredential == null) {
            return null;
        }
        try {
            return new ExpiringProfileKeyCredential(profileKeyCredential);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private StorageId getContactStorageIdFromResultSet(ResultSet resultSet) throws SQLException {
        final var storageId = resultSet.getBytes("storage_id");
        return StorageId.forContact(storageId);
    }

    private byte[] getStorageRecordFromResultSet(ResultSet resultSet) throws SQLException {
        return resultSet.getBytes("storage_record");
    }

    public interface RecipientMergeHandler {

        void mergeRecipients(
                final Connection connection, RecipientId recipientId, RecipientId toBeMergedRecipientId
        ) throws SQLException;
    }

    private class HelperStore implements MergeRecipientHelper.Store {

        private final Connection connection;

        public HelperStore(final Connection connection) {
            this.connection = connection;
        }

        @Override
        public Set<RecipientWithAddress> findAllByAddress(final RecipientAddress address) throws SQLException {
            return RecipientStore.this.findAllByAddress(connection, address);
        }

        @Override
        public RecipientId addNewRecipient(final RecipientAddress address) throws SQLException {
            return RecipientStore.this.addNewRecipient(connection, address);
        }

        @Override
        public void updateRecipientAddress(
                final RecipientId recipientId, final RecipientAddress address
        ) throws SQLException {
            RecipientStore.this.updateRecipientAddress(connection, recipientId, address);
        }

        @Override
        public void removeRecipientAddress(final RecipientId recipientId) throws SQLException {
            RecipientStore.this.removeRecipientAddress(connection, recipientId);
        }
    }
}
