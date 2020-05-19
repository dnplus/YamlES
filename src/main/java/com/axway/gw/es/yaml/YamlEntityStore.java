package com.axway.gw.es.yaml;

import com.axway.gw.es.yaml.dto.entity.EntityDTO;
import com.axway.gw.es.yaml.dto.type.TypeDTO;
import com.axway.gw.es.yaml.util.IndexedEntityTreeDelegate;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.vordel.es.*;
import com.vordel.es.impl.AbstractTypeStore;
import com.vordel.es.impl.EntityTypeMap;
import com.vordel.es.provider.file.IndexedEntityTree;
import com.vordel.es.util.ESPKCollection;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;

import static com.axway.gw.es.yaml.converters.EntityDTOConverter.METADATA_FILENAME;
import static com.axway.gw.es.yaml.converters.EntityDTOConverter.YAML_EXTENSION;
import static com.axway.gw.es.yaml.converters.EntityStoreESPKMapper.HIDDEN_FILE_PREFIX;
import static com.axway.gw.es.yaml.converters.EntityTypeDTOConverter.TYPES_FILE;
import static java.lang.System.currentTimeMillis;

@SuppressWarnings("WeakerAccess")
public class YamlEntityStore extends AbstractTypeStore implements EntityStore {

    private static final Logger LOG = LoggerFactory.getLogger(YamlEntityStore.class);
    public static final String SCHEME = "yaml:";
    public static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    static {
        YAML_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // YAML_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        // YAML_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);
    }

    private File rootLocation;
    private YamlPK root;
    private final Map<String, TypeDTO> types = new LinkedHashMap<>();
    private final IndexedEntityTreeDelegate entities = new IndexedEntityTreeDelegate(new IndexedEntityTree());
    private final EntityTypeMap typeMap = new EntityTypeMap();

    /**
     * Connect to an entity store.
     *
     * @param url         The Url of the EntityStore's backend provider.
     * @param credentials A property list of credentials required to connect. Will usually
     *                    contain a minimum of a username and password, but may contain other
     *                    implementation specific properties, such as key locations for SSL etc.
     *                    See the specific implementations of EntityStore for details as to the
     *                    contents of these properties.
     */
    @Override
    public void connect(String url, Properties credentials) {
        if (url == null || url.length() <= 0)
            throw new EntityStoreException("Provide URL to the directory for the yaml store");

        if (url.startsWith(SCHEME) && url.length() > SCHEME.length())
            url = url.substring(SCHEME.length());
        else
            throw new EntityStoreException("Invalid URL: " + url);
        LOG.info("Loading the url {}", url);
        try {
            rootLocation = new File(new URL(url).getFile());
        } catch (MalformedURLException e) {
            throw new EntityStoreException("Unable to parse URL", e);
        }
        try {
            long start = currentTimeMillis();
            loadTypes();
            loadEntities();
            long end = currentTimeMillis();
            LOG.info("Loaded ES with {} types and {} entities in {}ms", types.size(), entities.size(), (end - start));
        } catch (IOException e) {
            throw new EntityStoreException("Error opening yaml store", e);
        }
    }

    void setRootLocation(File rootLocation) {
        this.rootLocation = rootLocation;
    }

    public void loadEntities() {
        if (rootLocation == null)
            throw new EntityStoreException("root directory not set");
        // for the moment load everything into memory rather than ondemmand
        try {
            root = loadEntities(rootLocation, root);
        } catch (IOException e) {
            throw new EntityStoreException("Could not load YAML Entity Store", e);
        }
    }

    public YamlPK loadEntities(File dir, YamlPK parentPK) throws IOException {
        if (dir == null)
            throw new EntityStoreException("no directory to load entities from");

        LOG.debug("loading files from {}", dir);

        // create the parent entity using metadata.yaml
        Entity entity = createParentEntity(dir, parentPK);
        if (entity != null) {
            parentPK = (YamlPK)entity.getPK();
        }

        if (dir.toString().contains("Certificate Store")) {
            LOG.warn("Certificate Store is not handled");
        }

        final File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith(HIDDEN_FILE_PREFIX) || file.getPath().endsWith(METADATA_FILENAME) || file.getPath().endsWith("META-INF")) {
                    // skip META-INF (where types are stored)
                    // skip "__" prefixed files (a way to ignore some files)
                    // skip over metadata.yaml when loading entities (see createParentEntity() )
                    continue;
                }
                if (file.isDirectory()) {
                    loadEntities(file, parentPK);
                } else if (file.toString().endsWith(YAML_EXTENSION)) {
                    createEntity(file, parentPK);
                }
            }
        }

        return parentPK;
    }

    YamlEntity createEntity(File file, YamlPK parentPK) throws IOException {
        EntityDTO entityDTO = YAML_MAPPER.readValue(file, EntityDTO.class);
        File dir = file.getParentFile();

        YamlEntity entity = createEntity(entityDTO, parentPK, dir);

        if (entityDTO.getChildren() != null) {
            for (Map.Entry<String, EntityDTO> entry : entityDTO.getChildren().entrySet()) {
                createEntity(entry.getValue(), (YamlPK) entity.getPK(), dir);
            }
        }
        return entity;
    }

    private YamlEntity createEntity(EntityDTO entityDTO, YamlPK parentPK, File dir) throws IOException {
        entityDTO.getMeta().setTypeDTO(types.get(entityDTO.getMeta().getType()));
        YamlEntity entity = toYamlEntity(entityDTO, parentPK, dir);
        entities.add(parentPK, entity);
        return entity;
    }

    private YamlEntity createParentEntity(File dir, YamlPK parentPK) throws IOException {
        File metadata = new File(dir, METADATA_FILENAME);
        if (metadata.exists()) {
            return createEntity(metadata, parentPK);
        }
        return null;
    }

    public YamlEntity toYamlEntity(EntityDTO entityDTO, YamlPK parentPK, File dir) throws IOException {
        EntityType type = this.getTypeForName(entityDTO.getMeta().getType());
        YamlEntity entity = new YamlEntity(type);

        if (entityDTO.getFields() != null) {

            // fields
            for (Map.Entry<String, String> fieldEntry : entityDTO.getFields().entrySet()) {
                if (type.isConstantField(fieldEntry.getKey())) {
                    continue; // don't set constants
                }

                String rawFieldName = fieldEntry.getKey();
                String fieldValue = fieldEntry.getValue();
                String fieldName = StringUtils.substringBefore(rawFieldName, "#");

                FieldType fieldType = type.getFieldType(fieldName);
                if (fieldType.isRefType() || fieldType.isSoftRefType()) {
                    entity.setReferenceField(fieldName, new YamlPK(fieldValue));
                } else {
                    String content = fieldValue;
                    if (rawFieldName.contains("#ref")) {
                        byte[] data = Files.readAllBytes(dir.toPath().resolve(content));
                        if (rawFieldName.endsWith("#refbase64")) {
                            content = Base64.getEncoder().encodeToString(data);
                        } else {
                            content = new String(data);
                        }
                    }
                    entity.setField(fieldName, new Value[]{new Value(content)});
                }
            }
        }

        // pk
        YamlPK pk = toYamlPk(entityDTO, parentPK);
        entity.setPK(pk);
        entity.setParentPK(parentPK);
        return entity;
    }

    public static YamlPK toYamlPk(EntityDTO entityDTO, YamlPK parentPK) {
        return parentPK == null ? new YamlPK(entityDTO.getKeyDescription()) : new YamlPK(parentPK, entityDTO.getKeyDescription());
    }

    public void loadTypes() throws IOException {
        if (rootLocation == null)
            throw new EntityStoreException("root directory not set");
        TypeDTO typeDTO = YAML_MAPPER.readValue(new File(rootLocation, "META-INF/" + TYPES_FILE), TypeDTO.class);
        // for the moment load everything into memory rather than ondemmand
        loadType(typeDTO, null);
    }

    private YamlEntityType loadType(TypeDTO typeDTO, YamlEntityType parent) {
        types.put(typeDTO.getName(), typeDTO);
        YamlEntityType type = DTOToYamlESEntityTypeConverter.convert(typeDTO);
        type.setSuperType(parent);
        addType(type);
        for (TypeDTO yChild : typeDTO.getChildren()) {
            loadType(yChild, type);
        }
        return type;
    }

    /**
     * Get the identifier for the root Entity in the Store. Always returns
     * a valid PK, or throws an IllegalStateException if the underlying provider
     * exists but is not yet initialized.
     *
     * @return The root Entity identifier
     */
    @Override
    public ESPK getRootPK() {
        return root;
    }

    /**
     * Get a particular Entity from the Store, depending on its key.
     *
     * @param pk The unique identifier for the Entity
     * @return An Entity
     * @throws EntityStoreException If the specified Entity doesn't exist
     */
    @Override
    public Entity getEntity(ESPK pk) {
        return entities.getEntity(pk);
    }


    /**
     * Retrieve the subordinate Entities of the identified parent.
     *
     * @param pk                 The identifier of the Entities you wish to retrieve.
     * @param requiredFieldNames By specifying this, you can limit the amount of data
     *                           actually retrieved by the server.
     * @return An Entity
     */
    @Override
    public Entity getEntity(ESPK pk, String[] requiredFieldNames) {
        return getEntity(pk);
    }

    /**
     * Add an Entity to the Store.
     *
     * @param parentPK The identifier of the candidate parent Entity to this new Entity
     * @param entity   The entity you wish to store.
     * @return The identity of the new entity in the EntityStore. The Entity itself
     * will also be updated with this identifier, so successive calls to
     * {@link Entity#getPK() } will return the new identifier
     * @throws DuplicateKeysException If an entity with the same keys already exists in the store.
     */
    @Override
    public ESPK addEntity(ESPK parentPK, Entity entity) {
        return null;
    }

    /**
     * Replace an entity
     *
     * @param entity The candidate entity for replacement. The entity must already have
     *               been initialized via a call to getEntity, and its PK will be already
     *               set.
     */
    @Override
    public void updateEntity(Entity entity) {
        throw new UnsupportedOperationException();
    }

    /**
     * Delete an entity from the data store.
     *
     * @param pk Identity of the Entity to be deleted
     */
    @Override
    public void deleteEntity(ESPK pk) {
        throw new UnsupportedOperationException();
    }

    /**
     * List all children of a particular Entity.
     *
     * @param pk   The entity id of the parent whose children you wish to list
     * @param type Indicate the type of children you are interested in. "null"
     *             indicates that you wish to retrieve children of all types.
     * @return A Collection of zero or more child ESPKs
     */
    @Override
    public Collection<ESPK> listChildren(ESPK pk, EntityType type) {
        return findChildren(pk, null, type);
    }

    /**
     * Find all children of a particular Entity.
     *
     * @param parentId  The entity id of the parent whose children you wish to get
     * @param reqFields The Fields (including their values) you wish to do the search on. The
     *                  fields can have their data and/or references set.
     * @param type      Search only for Entities of the specified type.
     * @return A Collection of zero or more child ESPKs
     * @throws EntityStoreException If the pk is invalid.
     */
    @Override
    public Collection<ESPK> findChildren(ESPK parentId, Field[] reqFields, EntityType type) {
        Collection<Entity> children = entities.getChildren(parentId);
        ESPKCollection resList = new ESPKCollection(children.size());
        for (Entity e : children) {
            if (type == null || type.isAncestorOfType(e.getType())) {
                // We are looking for specific fields to match
                if (reqFields != null) {
                    if (e.containsFieldsWithDisjunctValues(reqFields))
                        resList.addESPK(e.getPK());
                } else {
                    resList.addESPK(e.getPK());
                }
            }
        }
        return resList;
    }


    /**
     * Get a Set of ESPKs which identify all Entities in the store which
     * contain a field or fields which hold a reference to the Entity specified
     *
     * @param targetEntityPK The ESPK of the Entity to which other Entities
     *                       may refer.
     * @return A Set of ESPKs of any Entities which hold a reference to the
     * target Entity.
     * @throws EntityStoreException If the PK is unknown.
     */
    @Override
    public Collection<ESPK> findReferringEntities(ESPK targetEntityPK) {
        throw new UnsupportedOperationException();
    }

    /**
     * Disconnect from the EntityStore. Releases any resources which may
     * have been associated with the connection.
     */
    @Override
    public void disconnect() {
        throw new UnsupportedOperationException();
    }

    /**
     * Connect to the EntityStore and bootstrap the store with the default
     * components required to implement a store. Any previous data in the
     * store will be wiped.
     *
     * @throws EntityStoreException if the underlying implementation cannot
     *                              perform its own initialization and relies on external administrative
     *                              steps to instantiate the schema components.
     */
    @Override
    public void initialize() {
        throw new UnsupportedOperationException();
    }

    /**
     * Import a serialized definition of the Store.
     *
     * @param stream An input stream from which to get the entity/type
     *               definitions
     * @return Collection&lt;ESPK&gt; a list of the newly imported objects.
     */
    @Override
    public Collection<ESPK> importData(InputStream stream) {
        throw new UnsupportedOperationException();
    }


    /**
     * Export the contents of the entity store to the specified output stream.
     *
     * @param out        The output stream to output to.
     * @param startNodes An array of the ESPKs which indicate the starting
     *                   points of the branches to export.
     *                   Null entries and duplicates will be ignored.
     *                   A null, zero-length array or array with no usable ESPKs will
     *                   result in no Entity data being exported, but types may still be exported
     *                   if specified in the flags.
     * @param flags      A set of flags to indicate what and how to export.
     * @throws EntityStoreException if the OutputStream is null.
     */
    @Override
    public void exportContents(OutputStream out, Collection<ESPK> startNodes, int flags) {
        throw new UnsupportedOperationException();
    }

    /**
     * Indicate to the Store that you want to start a transaction.
     *
     * @throws EntityStoreException if the underlying implementation was
     *                              unable to start a transaction
     */
    @Override
    public void startTransaction() {
        throw new UnsupportedOperationException();
    }

    /**
     * Indicate that you wish to have all operations on the Store since
     * the last startTransaction committed to the Store, and published to
     * the Store's listeners.
     *
     * @throws EntityStoreException If a transaction hasn't been started, or
     *                              the underlying implementation was unable to commit.
     */
    @Override
    public void commit() {
        throw new UnsupportedOperationException();
    }

    /**
     * Indicate that you wish to discard all changes to the Store since
     * the last startTransaction.
     *
     * @throws EntityStoreException If the underlying implementation was
     *                              unable to roll back your changes, or a transaction hasn't been
     *                              started
     */
    @Override
    public void rollback() {
        throw new UnsupportedOperationException();
    }

    /**
     * Register to listen to changes to the Entities in the Store.
     *
     * @param entityPK The Entity PK on which to listen for events
     * @param listener The listener for EntityStore change events
     */
    @Override
    public void registerChangeListener(ESPK entityPK, ESChangeListener listener) {
        throw new UnsupportedOperationException();
    }

    /**
     * Deregister from listening to changes to the Entities in the Store.
     *
     * @param entityPK The Entity PK from which we no longer listen to events
     * @param listener The listener for EntityStore change events, as initially
     *                 registered.
     */
    @Override
    public void deregisterChangeListener(ESPK entityPK, ESChangeListener listener) {
        throw new UnsupportedOperationException();
    }

    /**
     * Decode a stringified PK as per the implementation. To encode, just use
     * ESPK.toString()
     *
     * @param stringifiedPK The string version of the PK
     * @return an ESPK as per the particular implementation
     */
    @Override
    public ESPK decodePK(String stringifiedPK) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void deleteType(String typeName) {
        typeMap.removeType(typeName);
    }

    @Override
    protected void persistType(EntityType type) {
        typeMap.addType(type);
    }

    @Override
    protected String[] retrieveSubtypes(String typeName) {
        return typeMap.getSubtypeNames(typeName);
    }

    @Override
    protected EntityType retrieveType(String typeName) {
        return typeMap.getTypeForName(typeName);
    }


}
