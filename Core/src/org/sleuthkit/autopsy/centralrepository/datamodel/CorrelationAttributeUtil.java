/*
 * Central Repository
 *
 * Copyright 2017-2020 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.centralrepository.datamodel;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.openide.util.NbBundle.Messages;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.NoCurrentCaseException;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.BlackboardAttribute.ATTRIBUTE_TYPE;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.HashUtility;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;

/**
 * Utility class for correlation attributes in the central repository
 */
public class CorrelationAttributeUtil {

    private static final Logger logger = Logger.getLogger(CorrelationAttributeUtil.class.getName());

    @Messages({"EamArtifactUtil.emailaddresses.text=Email Addresses"})
    public static String getEmailAddressAttrString() {
        return Bundle.EamArtifactUtil_emailaddresses_text();
    }

    /**
     * Examines an artifact and makes zero to many correlation attribute
     * instances from its attributes.
     *
     * IMPORTANT: The correlation attribute instances are NOT added to the
     * central repository by this method.
     *
     * @param artifact An artifact.
     *
     * @return A list, possibly empty, of correlation attribute instances for
     *         the artifact.
     */
    public static List<CorrelationAttributeInstance> makeAttrsForArtifact(BlackboardArtifact artifact) {
        List<CorrelationAttributeInstance> correlationAttrs = new ArrayList<>();
        try {
            /*
             * If the artifact is an interesting artifact hit, examine the
             * interesting artifact, not the hit "meta-artifact."
             */
            BlackboardArtifact artToExamine = null;
            if (BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_ARTIFACT_HIT.getTypeID() == artifact.getArtifactTypeID()) {
                BlackboardAttribute assocArtifactAttr = artifact.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT));
                if (assocArtifactAttr != null) {
                    artToExamine = Case.getCurrentCaseThrows().getSleuthkitCase().getBlackboardArtifact(assocArtifactAttr.getValueLong());
                }
            } else {
                artToExamine = artifact;
            }

            /*
             * 
             */
            if (artToExamine != null) {
                int artifactTypeID = artToExamine.getArtifactTypeID();                
                if (artifactTypeID == ARTIFACT_TYPE.TSK_KEYWORD_HIT.getTypeID()) {
                    BlackboardAttribute setNameAttr = artToExamine.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_SET_NAME));
                    if (setNameAttr != null && CorrelationAttributeUtil.getEmailAddressAttrString().equals(setNameAttr.getValueString())) {
                        makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_KEYWORD, CorrelationAttributeInstance.EMAIL_TYPE_ID);
                    }
                    
                } else if (artifactTypeID == ARTIFACT_TYPE.TSK_WEB_BOOKMARK.getTypeID()
                        || artifactTypeID == ARTIFACT_TYPE.TSK_WEB_COOKIE.getTypeID()
                        || artifactTypeID == ARTIFACT_TYPE.TSK_WEB_DOWNLOAD.getTypeID()
                        || artifactTypeID == ARTIFACT_TYPE.TSK_WEB_HISTORY.getTypeID()) {
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DOMAIN, CorrelationAttributeInstance.DOMAIN_TYPE_ID);

                } else if (artifactTypeID == ARTIFACT_TYPE.TSK_CONTACT.getTypeID()
                        || artifactTypeID == ARTIFACT_TYPE.TSK_CALLLOG.getTypeID()
                        || artifactTypeID == ARTIFACT_TYPE.TSK_MESSAGE.getTypeID()) {
                    String value = null;
                    if (null != artToExamine.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PHONE_NUMBER))) {
                        value = artToExamine.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PHONE_NUMBER)).getValueString();
                    } else if (null != artToExamine.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM))) {
                        value = artToExamine.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM)).getValueString();
                    } else if (null != artToExamine.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO))) {
                        value = artToExamine.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO)).getValueString();
                    }
                    // Remove all non-numeric symbols to semi-normalize phone numbers, preserving leading "+" character
                    if (value != null) {
                        String newValue = value.replaceAll("\\D", "");
                        if (value.startsWith("+")) {
                            newValue = "+" + newValue;
                        }
                        value = newValue;
                        // Only add the correlation attribute if the resulting phone number large enough to be of use
                        // (these 3-5 digit numbers can be valid, but are not useful for correlation)
                        if (value.length() > 5) {
                            CorrelationAttributeInstance inst = makeCorrelationAttributeInstanceUsingTypeValue(artToExamine, CentralRepository.getInstance().getCorrelationTypeById(CorrelationAttributeInstance.PHONE_TYPE_ID), value);
                            if (inst != null) {
                                correlationAttrs.add(inst);
                            }
                        }
                    }

                } else if (artifactTypeID == ARTIFACT_TYPE.TSK_DEVICE_ATTACHED.getTypeID()) {
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DEVICE_ID, CorrelationAttributeInstance.USBID_TYPE_ID);
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_MAC_ADDRESS, CorrelationAttributeInstance.MAC_TYPE_ID);

                } else if (artifactTypeID == ARTIFACT_TYPE.TSK_WIFI_NETWORK.getTypeID()) {
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_SSID, CorrelationAttributeInstance.SSID_TYPE_ID);

                } else if (artifactTypeID == ARTIFACT_TYPE.TSK_WIFI_NETWORK_ADAPTER.getTypeID()
                        || artifactTypeID == ARTIFACT_TYPE.TSK_BLUETOOTH_PAIRING.getTypeID()
                        || artifactTypeID == ARTIFACT_TYPE.TSK_BLUETOOTH_ADAPTER.getTypeID()) {
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_MAC_ADDRESS, CorrelationAttributeInstance.MAC_TYPE_ID);

                } else if (artifactTypeID == ARTIFACT_TYPE.TSK_DEVICE_INFO.getTypeID()) {
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_IMEI, CorrelationAttributeInstance.IMEI_TYPE_ID);
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_IMSI, CorrelationAttributeInstance.IMSI_TYPE_ID);
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ICCID, CorrelationAttributeInstance.ICCID_TYPE_ID);

                } else if (artifactTypeID == ARTIFACT_TYPE.TSK_SIM_ATTACHED.getTypeID()) {
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_IMSI, CorrelationAttributeInstance.IMSI_TYPE_ID);
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ICCID, CorrelationAttributeInstance.ICCID_TYPE_ID);

                } else if (artifactTypeID == ARTIFACT_TYPE.TSK_WEB_FORM_ADDRESS.getTypeID()) {
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PHONE_NUMBER, CorrelationAttributeInstance.PHONE_TYPE_ID);
                    makeCorrAttrFromArtifactAttr(correlationAttrs, artToExamine, BlackboardAttribute.ATTRIBUTE_TYPE.TSK_EMAIL, CorrelationAttributeInstance.EMAIL_TYPE_ID);

                } else if (artifactTypeID == ARTIFACT_TYPE.TSK_ACCOUNT.getTypeID()) {
                    // RJCTODO: Make a correlation attribute by switching on account type 
                }
            }
        } catch (CentralRepoException ex) {
            logger.log(Level.SEVERE, "Error getting defined correlation types.", ex); // NON-NLS
            return correlationAttrs;
        } catch (TskCoreException ex) {
            logger.log(Level.SEVERE, "Error getting attribute while getting type from BlackboardArtifact.", ex); // NON-NLS
            return correlationAttrs;
        } catch (NoCurrentCaseException ex) {
            logger.log(Level.SEVERE, "Exception while getting open case.", ex); // NON-NLS
            return correlationAttrs;
        }
        return correlationAttrs;
    }

    /**
     * Add a CorrelationAttributeInstance of the specified type to the provided
     * list if the artifactForInstance has an Attribute of the given type with a
     * non empty value.
     *
     * @param eamArtifacts    the list of CorrelationAttributeInstance objects
     *                        which should be added to
     * @param artifact        the blackboard artifactForInstance which we are
     *                        creating a CorrelationAttributeInstance for
     * @param bbAttributeType the type of BlackboardAttribute we expect to exist
     *                        for a CorrelationAttributeInstance of this type
     *                        generated from this Blackboard Artifact
     * @param typeId          the integer type id of the
     *                        CorrelationAttributeInstance type
     *
     * @throws CentralRepoException
     * @throws TskCoreException
     */
    private static void makeCorrAttrFromArtifactAttr(List<CorrelationAttributeInstance> eamArtifacts, BlackboardArtifact artifact, ATTRIBUTE_TYPE bbAttributeType, int typeId) throws CentralRepoException, TskCoreException {
        BlackboardAttribute attribute = artifact.getAttribute(new BlackboardAttribute.Type(bbAttributeType));
        if (attribute != null) {
            String value = attribute.getValueString();
            if ((null != value) && (value.isEmpty() == false)) {
                CorrelationAttributeInstance inst = makeCorrelationAttributeInstanceUsingTypeValue(artifact, CentralRepository.getInstance().getCorrelationTypeById(typeId), value);
                if (inst != null) {
                    eamArtifacts.add(inst);
                }
            }
        }
    }

    /**
     * Uses the determined type and vallue, then looks up instance details to
     * create proper CorrelationAttributeInstance.
     *
     * @param bbArtifact      the blackboard artifactForInstance
     * @param correlationType the given type
     * @param value           the artifactForInstance value
     *
     * @return CorrelationAttributeInstance from details, or null if validation
     *         failed or another error occurred
     */
    private static CorrelationAttributeInstance makeCorrelationAttributeInstanceUsingTypeValue(BlackboardArtifact bbArtifact, CorrelationAttributeInstance.Type correlationType, String value) {
        try {
            Case currentCase = Case.getCurrentCaseThrows();
            AbstractFile bbSourceFile = currentCase.getSleuthkitCase().getAbstractFileById(bbArtifact.getObjectID());
            if (null == bbSourceFile) {
                logger.log(Level.SEVERE, "Error creating artifact instance. Abstract File was null."); // NON-NLS
                return null;
            }

            // make an instance for the BB source file
            CorrelationCase correlationCase = CentralRepository.getInstance().getCase(Case.getCurrentCaseThrows());
            return new CorrelationAttributeInstance(
                    correlationType,
                    value,
                    correlationCase,
                    CorrelationDataSource.fromTSKDataSource(correlationCase, bbSourceFile.getDataSource()),
                    bbSourceFile.getParentPath() + bbSourceFile.getName(),
                    "",
                    TskData.FileKnown.UNKNOWN,
                    bbSourceFile.getId());

        } catch (TskCoreException ex) {
            logger.log(Level.SEVERE, "Error getting AbstractFile for artifact: " + bbArtifact.toString(), ex); // NON-NLS
            return null;
        } catch (CentralRepoException | CorrelationAttributeNormalizationException ex) {
            logger.log(Level.WARNING, "Error creating artifact instance for artifact: " + bbArtifact.toString(), ex); // NON-NLS
            return null;
        } catch (NoCurrentCaseException ex) {
            logger.log(Level.SEVERE, "Case is closed.", ex); // NON-NLS
            return null;
        }
    }

    /**
     * Retrieve CorrelationAttribute from the given Content.
     *
     * @param content The content object
     *
     * @return The new CorrelationAttribute, or null if retrieval failed.
     */
    public static CorrelationAttributeInstance getInstanceFromContent(Content content) {

        if (!(content instanceof AbstractFile)) {
            return null;
        }

        final AbstractFile file = (AbstractFile) content;

        if (!isSupportedAbstractFileType(file)) {
            return null;
        }

        CorrelationAttributeInstance.Type type;
        CorrelationCase correlationCase;
        CorrelationDataSource correlationDataSource;

        try {
            type = CentralRepository.getInstance().getCorrelationTypeById(CorrelationAttributeInstance.FILES_TYPE_ID);
            correlationCase = CentralRepository.getInstance().getCase(Case.getCurrentCaseThrows());
            if (null == correlationCase) {
                //if the correlationCase is not in the Central repo then attributes generated in relation to it will not be
                return null;
            }
            correlationDataSource = CorrelationDataSource.fromTSKDataSource(correlationCase, file.getDataSource());
        } catch (TskCoreException | CentralRepoException ex) {
            logger.log(Level.SEVERE, "Error retrieving correlation attribute.", ex);
            return null;
        } catch (NoCurrentCaseException ex) {
            logger.log(Level.SEVERE, "Case is closed.", ex);
            return null;
        }

        CorrelationAttributeInstance correlationAttributeInstance;
        try {
            correlationAttributeInstance = CentralRepository.getInstance().getCorrelationAttributeInstance(type, correlationCase, correlationDataSource, file.getId());
        } catch (CentralRepoException | CorrelationAttributeNormalizationException ex) {
            logger.log(Level.WARNING, String.format(
                    "Correlation attribute could not be retrieved for '%s' (id=%d): ",
                    content.getName(), content.getId()), ex);
            return null;
        }
        //if there was no correlation attribute found for the item using object_id then check for attributes added with schema 1,1 which lack object_id  
        if (correlationAttributeInstance == null && file.getMd5Hash() != null) {
            String filePath = (file.getParentPath() + file.getName()).toLowerCase();
            try {
                correlationAttributeInstance = CentralRepository.getInstance().getCorrelationAttributeInstance(type, correlationCase, correlationDataSource, file.getMd5Hash(), filePath);
            } catch (CentralRepoException | CorrelationAttributeNormalizationException ex) {
                logger.log(Level.WARNING, String.format(
                        "Correlation attribute could not be retrieved for '%s' (id=%d): ",
                        content.getName(), content.getId()), ex);
                return null;
            }
        }

        return correlationAttributeInstance;
    }

    /**
     * Create an EamArtifact from the given Content. Will return null if an
     * artifactForInstance can not be created - this is not necessarily an error
     * case, it just means an artifactForInstance can't be made. If creation
     * fails due to an error (and not that the file is the wrong type or it has
     * no hash), the error will be logged before returning.
     *
     * Does not add the artifactForInstance to the database.
     *
     * @param content The content object
     *
     * @return The new EamArtifact or null if creation failed
     */
    public static CorrelationAttributeInstance makeInstanceFromContent(Content content) {

        if (!(content instanceof AbstractFile)) {
            return null;
        }

        final AbstractFile af = (AbstractFile) content;

        if (!isSupportedAbstractFileType(af)) {
            return null;
        }

        // We need a hash to make the artifactForInstance
        String md5 = af.getMd5Hash();
        if (md5 == null || md5.isEmpty() || HashUtility.isNoDataMd5(md5)) {
            return null;
        }

        try {
            CorrelationAttributeInstance.Type filesType = CentralRepository.getInstance().getCorrelationTypeById(CorrelationAttributeInstance.FILES_TYPE_ID);

            CorrelationCase correlationCase = CentralRepository.getInstance().getCase(Case.getCurrentCaseThrows());
            return new CorrelationAttributeInstance(
                    filesType,
                    af.getMd5Hash(),
                    correlationCase,
                    CorrelationDataSource.fromTSKDataSource(correlationCase, af.getDataSource()),
                    af.getParentPath() + af.getName(),
                    "",
                    TskData.FileKnown.UNKNOWN,
                    af.getId());

        } catch (TskCoreException | CentralRepoException | CorrelationAttributeNormalizationException ex) {
            logger.log(Level.SEVERE, "Error making correlation attribute.", ex);
            return null;
        } catch (NoCurrentCaseException ex) {
            logger.log(Level.SEVERE, "Case is closed.", ex);
            return null;
        }
    }

    /**
     * Check whether the given abstract file should be processed for the central
     * repository.
     *
     * @param file The file to test
     *
     * @return true if the file should be added to the central repo, false
     *         otherwise
     */
    public static boolean isSupportedAbstractFileType(AbstractFile file) {
        if (file == null) {
            return false;
        }
        switch (file.getType()) {
            case UNALLOC_BLOCKS:
            case UNUSED_BLOCKS:
            case SLACK:
            case VIRTUAL_DIR:
            case LOCAL_DIR:
                return false;
            case CARVED:
            case DERIVED:
            case LOCAL:
            case LAYOUT_FILE:
                return true;
            case FS:
                return file.isMetaFlagSet(TskData.TSK_FS_META_FLAG_ENUM.ALLOC);
            default:
                logger.log(Level.WARNING, "Unexpected file type {0}", file.getType().getName());
                return false;
        }
    }

    /**
     * Constructs a new EamArtifactUtil
     */
    private CorrelationAttributeUtil() {
        //empty constructor
    }
}
