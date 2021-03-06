/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.apache.atlas.eventmapper;

import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.AtlasRelationship;
import org.apache.atlas.model.instance.AtlasRelationshipHeader;
import org.apache.atlas.model.notification.EntityNotification;
import org.apache.atlas.notification.entity.EntityMessageDeserializer;
import org.apache.atlas.utils.AtlasJson;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.odpi.egeria.connectors.apache.atlas.repositoryconnector.ApacheAtlasOMRSMetadataCollection;
import org.odpi.egeria.connectors.apache.atlas.repositoryconnector.ApacheAtlasOMRSRepositoryConnector;
import org.odpi.egeria.connectors.apache.atlas.repositoryconnector.mapping.EntityMappingAtlas2OMRS;
import org.odpi.egeria.connectors.apache.atlas.repositoryconnector.mapping.RelationshipMapping;
import org.odpi.egeria.connectors.apache.atlas.repositoryconnector.stores.TypeDefStore;
import org.odpi.openmetadata.frameworks.connectors.Connector;
import org.odpi.openmetadata.frameworks.connectors.VirtualConnectorExtension;
import org.odpi.openmetadata.frameworks.connectors.ffdc.ConnectorCheckedException;
import org.odpi.openmetadata.repositoryservices.auditlog.OMRSAuditCode;
import org.odpi.openmetadata.repositoryservices.connectors.openmetadatatopic.OpenMetadataTopicConnector;
import org.odpi.openmetadata.repositoryservices.connectors.openmetadatatopic.OpenMetadataTopicListener;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.*;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.repositoryconnector.OMRSRepositoryConnector;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.repositoryeventmapper.OMRSRepositoryEventMapperBase;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.RepositoryErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * ApacheAtlasOMRSRepositoryEventMapper supports the event mapper function for Apache Atlas
 * when used as an open metadata repository.
 */
public class ApacheAtlasOMRSRepositoryEventMapper extends OMRSRepositoryEventMapperBase
        implements VirtualConnectorExtension, OpenMetadataTopicListener {

    private List<Connector> embeddedConnectors = null;
    private List<OpenMetadataTopicConnector> eventBusConnectors = new ArrayList<>();

    private static final Logger log = LoggerFactory.getLogger(ApacheAtlasOMRSRepositoryEventMapper.class);

    private String sourceName;
    private ApacheAtlasOMRSRepositoryConnector atlasRepositoryConnector;
    private ApacheAtlasOMRSMetadataCollection atlasMetadataCollection;
    private TypeDefStore typeDefStore;
    private String metadataCollectionId;
    private String originatorServerName;
    private String originatorServerType;

    private Properties atlasKafkaProperties;
    private String atlasKafkaBootstrap;
    private String atlasKafkaTopic;

    private EntityMessageDeserializer deserializer;

    /**
     * Default constructor
     */
    public ApacheAtlasOMRSRepositoryEventMapper() {
        super();
        this.sourceName = "ApacheAtlasOMRSRepositoryEventMapper";
    }


    /**
     * Pass additional information to the connector needed to process events.
     *
     * @param repositoryEventMapperName repository event mapper name used for the source of the OMRS events.
     * @param repositoryConnector ths is the connector to the local repository that the event mapper is processing
     *                            events from.  The repository connector is used to retrieve additional information
     *                            necessary to fill out the OMRS Events.
     */
    @Override
    public void initialize(String                  repositoryEventMapperName,
                           OMRSRepositoryConnector repositoryConnector) {

        super.initialize(repositoryEventMapperName, repositoryConnector);
        log.info("Apache Atlas Event Mapper initializing...");

        // Setup Apache Atlas Repository connectivity
        this.atlasRepositoryConnector = (ApacheAtlasOMRSRepositoryConnector) this.repositoryConnector;
        this.atlasKafkaTopic = "ATLAS_ENTITIES";

        // Retrieve connection details to configure Kafka connectivity
        this.atlasKafkaBootstrap = this.connectionBean.getEndpoint().getAddress();
        atlasKafkaProperties = new Properties();
        atlasKafkaProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, atlasKafkaBootstrap);
        atlasKafkaProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "ApacheAtlasOMRSRepositoryEventMapper_consumer");
        atlasKafkaProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        atlasKafkaProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        this.deserializer = new EntityMessageDeserializer();

    }


    /**
     * Indicates that the connector is completely configured and can begin processing.
     *
     * @throws ConnectorCheckedException there is a problem within the connector.
     */
    @Override
    public void start() throws ConnectorCheckedException {

        super.start();
        log.info("Apache Atlas Event Mapper starting...");
        try {
            this.atlasMetadataCollection = (ApacheAtlasOMRSMetadataCollection) atlasRepositoryConnector.getMetadataCollection();
        } catch (RepositoryErrorException e) {
            throw new ConnectorCheckedException(
                    e.getReportedHTTPCode(),
                    this.getClass().getCanonicalName(),
                    e.getReportingActionDescription(),
                    e.getErrorMessage(),
                    e.getReportedSystemAction(),
                    e.getReportedUserAction(),
                    e
            );
        }
        this.typeDefStore = atlasMetadataCollection.getTypeDefStore();
        this.metadataCollectionId = atlasRepositoryConnector.getMetadataCollectionId();
        this.originatorServerName = atlasRepositoryConnector.getServerName();
        this.originatorServerType = atlasRepositoryConnector.getServerType();

        final String  methodName = "start";

        /*
         * Step through the embedded connectors, selecting only the OpenMetadataTopicConnectors
         * to use.
         */
        if (embeddedConnectors != null) {

            for (Connector  embeddedConnector : embeddedConnectors) {
                if (embeddedConnector instanceof OpenMetadataTopicConnector) {
                    /*
                     * Successfully found an event bus connector of the right type.
                     */
                    OpenMetadataTopicConnector realTopicConnector = (OpenMetadataTopicConnector)embeddedConnector;

                    String   topicName = realTopicConnector.registerListener(this);
                    this.eventBusConnectors.add(realTopicConnector);

                    if (auditLog != null) {
                        OMRSAuditCode auditCode = OMRSAuditCode.EVENT_MAPPER_LISTENER_REGISTERED;
                        auditLog.logRecord(methodName,
                                auditCode.getLogMessageId(),
                                auditCode.getSeverity(),
                                auditCode.getFormattedLogMessage(repositoryEventMapperName, topicName),
                                this.getConnection().toString(),
                                auditCode.getSystemAction(),
                                auditCode.getUserAction());
                    }
                }
            }
        }

        /*
         * OMRSTopicConnector needs at least one event bus connector to operate successfully.
         */
        if (this.eventBusConnectors.isEmpty() && auditLog != null) {
            OMRSAuditCode auditCode = OMRSAuditCode.EVENT_MAPPER_LISTENER_DEAF;
            auditLog.logRecord(methodName,
                    auditCode.getLogMessageId(),
                    auditCode.getSeverity(),
                    auditCode.getFormattedLogMessage(repositoryEventMapperName),
                    this.getConnection().toString(),
                    auditCode.getSystemAction(),
                    auditCode.getUserAction());
        }

        log.info("Starting consumption from Apache Atlas Kafka bus.");
        new Thread(new KafkaConsumerThread()).start();

    }


    /**
     * Class to support multi-threaded consumption of Apache Atlas Kafka events.
     */
    private class KafkaConsumerThread implements Runnable {

        /**
         * Read Apache Atlas Kafka events.
         */
        @Override
        public void run() {

            log.info("Starting Apache Atlas Event Mapper consumer thread.");
            final Consumer<Long, String> consumer = new KafkaConsumer<>(atlasKafkaProperties);
            consumer.subscribe(Collections.singletonList(atlasKafkaTopic));

            while (true) {
                try {
                    ConsumerRecords<Long, String> events = consumer.poll(100);
                    for (ConsumerRecord<Long, String> event : events) {
                        processEvent(event.value());
                    }
                } catch (Exception e) {
                    log.error("Failed trying to consume Apache Atlas events from Kafka.", e);
                }
            }
        }

    }


    /**
     * Registers itself as a listener of any OpenMetadataTopicConnectors that are passed as
     * embedded connectors.
     *
     * @param embeddedConnectors  list of connectors
     */
    @Override
    public void initializeEmbeddedConnectors(List<Connector> embeddedConnectors) {
        this.embeddedConnectors = embeddedConnectors;
    }


    /**
     * Method to pass an event received on topic.
     *
     * @param event inbound event
     */
    @Override
    public void processEvent(String event) {
        if (log.isInfoEnabled()) { log.info("Processing event: {}", event); }

        // Need to call this with just the 'message' portion of the payload, it seems?
        EntityNotification atlasEvent = deserializer.deserialize(event);
        EntityNotification.EntityNotificationV2 entityNotification = (EntityNotification.EntityNotificationV2) atlasEvent;

        if (entityNotification != null) {

            // TODO: create examples for and test commented-out operations

            switch(entityNotification.getOperationType()) {
                case ENTITY_CREATE:
                    processNewEntity(entityNotification.getEntity());
                    break;
                case ENTITY_UPDATE:
                    processUpdatedEntity(entityNotification.getEntity());
                    break;
                /*case ENTITY_DELETE:
                    break;
                case CLASSIFICATION_ADD:
                    break;
                case CLASSIFICATION_UPDATE:
                    break;
                case CLASSIFICATION_DELETE:
                    break;*/
                case RELATIONSHIP_CREATE:
                    processNewRelationship(entityNotification.getRelationship());
                    break;
                /*case RELATIONSHIP_UPDATE:
                    break;
                case RELATIONSHIP_DELETE:
                    break;*/
                default:
                    log.warn("Unrecognized operation type from Apache Atlas: {}", event);
                    break;
            }

        } else {
            log.error("Unrecognized event type from Apache Atlas: {}", event);
        }

    }

    /**
     * Processes and sends an OMRS event for the new Apache Atlas entity.
     *
     * @param atlasEntityHeader the new Apache Atlas entity information
     */
    private void processNewEntity(AtlasEntityHeader atlasEntityHeader) {
        // Send an event for every entity: normal and generated
        String atlasTypeName = atlasEntityHeader.getTypeName();
        Map<String, String> omrsTypesByPrefix = typeDefStore.getAllMappedOMRSTypeDefNames(atlasTypeName);
        for (String prefix : omrsTypesByPrefix.keySet()) {
            EntityDetail entityDetail = getMappedEntity(atlasEntityHeader, prefix);
            if (entityDetail != null) {
                repositoryEventProcessor.processNewEntityEvent(
                        sourceName,
                        metadataCollectionId,
                        originatorServerName,
                        originatorServerType,
                        localOrganizationName,
                        entityDetail
                );
                if (prefix != null) {
                    List<Relationship> generatedRelationships = getGeneratedRelationshipsForEntity(atlasEntityHeader, entityDetail);
                    for (Relationship generatedRelationship : generatedRelationships) {
                        repositoryEventProcessor.processNewRelationshipEvent(
                                sourceName,
                                metadataCollectionId,
                                originatorServerName,
                                originatorServerType,
                                localOrganizationName,
                                generatedRelationship
                        );
                    }
                }
            }
        }
    }

    /**
     * Processes and sends an OMRS event for the updated Apache Atlas entity.
     *
     * @param atlasEntityHeader the updated Apache Atlas entity information
     */
    private void processUpdatedEntity(AtlasEntityHeader atlasEntityHeader) {
        // Send an event for every entity: normal and generated
        Map<String, String> omrsTypesByPrefix = typeDefStore.getAllMappedOMRSTypeDefNames(atlasEntityHeader.getTypeName());
        for (String prefix : omrsTypesByPrefix.keySet()) {
            EntityDetail entityDetail = getMappedEntity(atlasEntityHeader, prefix);
            if (entityDetail != null) {
                // TODO: find a way to pull back the old version to send in the update event
                repositoryEventProcessor.processUpdatedEntityEvent(
                        sourceName,
                        metadataCollectionId,
                        originatorServerName,
                        originatorServerType,
                        localOrganizationName,
                        null,
                        entityDetail
                );
                if (prefix != null) {
                    List<Relationship> generatedRelationships = getGeneratedRelationshipsForEntity(atlasEntityHeader, entityDetail);
                    for (Relationship generatedRelationship : generatedRelationships) {
                        // TODO: find a way to pull back the old version to send in the update event
                        repositoryEventProcessor.processUpdatedRelationshipEvent(
                                sourceName,
                                metadataCollectionId,
                                originatorServerName,
                                originatorServerType,
                                localOrganizationName,
                                null,
                                generatedRelationship
                        );
                    }
                }
            }
        }
    }

    /**
     * Generate any pseudo-relationships for the provided entity.
     *
     * @param atlasEntityHeader
     * @param entityDetail
     * @return
     */
    private List<Relationship> getGeneratedRelationshipsForEntity(AtlasEntityHeader atlasEntityHeader,
                                                                  EntityDetail entityDetail) {

        String atlasTypeName = atlasEntityHeader.getTypeName();
        List<Relationship> generatedRelationships = new ArrayList<>();
        Map<String, TypeDefStore.EndpointMapping> mappings = typeDefStore.getAllEndpointMappingsFromAtlasName(atlasTypeName);
        for (Map.Entry<String, TypeDefStore.EndpointMapping> entry : mappings.entrySet()) {
            String relationshipPrefix = entry.getKey();
            if (relationshipPrefix != null) {
                String relationshipGUID = ApacheAtlasOMRSMetadataCollection.generateGuidWithPrefix(relationshipPrefix, atlasEntityHeader.getGuid());
                TypeDefStore.EndpointMapping mapping = entry.getValue();
                EntityProxy ep1 = RelationshipMapping.getEntityProxyForObject(
                        atlasRepositoryConnector,
                        typeDefStore,
                        new AtlasEntity(atlasEntityHeader),
                        mapping.getPrefixOne(),
                        null
                );
                EntityProxy ep2 = RelationshipMapping.getEntityProxyForObject(
                        atlasRepositoryConnector,
                        typeDefStore,
                        new AtlasEntity(atlasEntityHeader),
                        mapping.getPrefixTwo(),
                        null
                );
                try {
                    Relationship generatedRelationship = RelationshipMapping.getRelationship(atlasRepositoryConnector,
                            typeDefStore,
                            mapping.getOmrsRelationshipTypeName(),
                            relationshipGUID,
                            InstanceStatus.ACTIVE,
                            ep1,
                            ep2,
                            entityDetail.getCreatedBy(),
                            entityDetail.getUpdatedBy(),
                            entityDetail.getCreateTime(),
                            entityDetail.getUpdateTime(),
                            null);
                    if (generatedRelationship != null) {
                        generatedRelationships.add(generatedRelationship);
                    } else {
                        log.warn("Unable to create generated relationship with prefix {}, for entity: {}", relationshipPrefix, entityDetail.getGUID());
                    }
                } catch(RepositoryErrorException e){
                    log.error("Unable to create generated relationship with prefix {}, for entity: {}", relationshipPrefix, entityDetail.getGUID(), e);
                }
            }
        }
        return generatedRelationships;

    }

    /**
     * Retrieve the mapped OMRS entity for the provided Apache Atlas entity.
     *
     * @param atlasEntityHeader the Apache Atlas entity to translate to OMRS
     * @return EntityDetail
     */
    private EntityDetail getMappedEntity(AtlasEntityHeader atlasEntityHeader, String prefix) {
        EntityDetail result = null;
        AtlasEntity.AtlasEntityWithExtInfo atlasEntity = atlasRepositoryConnector.getEntityByGUID(atlasEntityHeader.getGuid(), false, true, true);
        EntityMappingAtlas2OMRS mapping = new EntityMappingAtlas2OMRS(
                atlasRepositoryConnector,
                atlasMetadataCollection.getTypeDefStore(),
                atlasMetadataCollection.getAttributeTypeDefStore(),
                atlasEntity,
                prefix,
                null
        );
        try {
            result = mapping.getEntityDetail();
        } catch (RepositoryErrorException e) {
            log.error("Unable to map entity to OMRS EntityDetail: {}", atlasEntity, e);
        }
        return result;
    }

    /**
     * Processes and sends an OMRS event for the new Apache Atlas relationship.
     *
     * @param atlasRelationshipHeader the new Apache Atlas relationship information
     */
    private void processNewRelationship(AtlasRelationshipHeader atlasRelationshipHeader) {
        Relationship relationship = getMappedRelationship(atlasRelationshipHeader);
        if (relationship != null) {
            repositoryEventProcessor.processNewRelationshipEvent(
                    sourceName,
                    metadataCollectionId,
                    originatorServerName,
                    originatorServerType,
                    localOrganizationName,
                    relationship
            );
        }
    }

    /**
     * Retrieve the mapped OMRS relationship for the provided Apache Atlas relationship.
     *
     * @param atlasRelationshipHeader the Apache Atlas relationship to translate to OMRS
     * @return Relationship
     */
    private Relationship getMappedRelationship(AtlasRelationshipHeader atlasRelationshipHeader) {
        Relationship result = null;
        AtlasRelationship.AtlasRelationshipWithExtInfo atlasRelationship = atlasRepositoryConnector.getRelationshipByGUID(atlasRelationshipHeader.getGuid(), true);
        RelationshipMapping mapping = new RelationshipMapping(
                atlasRepositoryConnector,
                atlasMetadataCollection.getTypeDefStore(),
                atlasMetadataCollection.getAttributeTypeDefStore(),
                atlasRelationship,
                null
        );
        try {
            result = mapping.getRelationship();
        } catch (RepositoryErrorException e) {
            log.error("Unable to map relationship to OMRS Relationship: {}", atlasRelationship, e);
        }
        return result;
    }

}
