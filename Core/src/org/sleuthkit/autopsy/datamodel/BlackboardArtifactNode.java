/*
 * Autopsy Forensic Browser
 *
 * Copyright 2012-2020 Basis Technology Corp.
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
package org.sleuthkit.autopsy.datamodel;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;
import javax.swing.Action;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.openide.nodes.Sheet;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.util.WeakListeners;
import org.openide.util.lookup.Lookups;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.NoCurrentCaseException;
import org.sleuthkit.autopsy.casemodule.events.BlackBoardArtifactTagAddedEvent;
import org.sleuthkit.autopsy.casemodule.events.BlackBoardArtifactTagDeletedEvent;
import org.sleuthkit.autopsy.casemodule.events.CommentChangedEvent;
import org.sleuthkit.autopsy.casemodule.events.ContentTagAddedEvent;
import org.sleuthkit.autopsy.casemodule.events.ContentTagDeletedEvent;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationAttributeInstance;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationAttributeInstance.Type;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationAttributeNormalizationException;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationAttributeUtil;
import org.sleuthkit.autopsy.centralrepository.datamodel.CentralRepoException;
import org.sleuthkit.autopsy.core.UserPreferences;
import org.sleuthkit.autopsy.corecomponents.DataResultViewerTable;
import org.sleuthkit.autopsy.corecomponents.DataResultViewerTable.Score;
import org.sleuthkit.autopsy.coreutils.Logger;
import static org.sleuthkit.autopsy.datamodel.DisplayableItemNode.findLinked;
import org.sleuthkit.autopsy.corecomponents.DataResultViewerTable.HasCommentStatus;
import static org.sleuthkit.autopsy.datamodel.AbstractContentNode.backgroundTasksPool;
import org.sleuthkit.autopsy.modules.hashdatabase.HashDbManager;
import org.sleuthkit.autopsy.timeline.actions.ViewArtifactInTimelineAction;
import org.sleuthkit.autopsy.timeline.actions.ViewFileInTimelineAction;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.BlackboardAttribute.ATTRIBUTE_TYPE;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.Tag;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;
import org.sleuthkit.autopsy.centralrepository.datamodel.CentralRepository;

/**
 * A BlackboardArtifactNode is an AbstractNode implementation that can be used
 * to represent an artifact of any type.
 */
public class BlackboardArtifactNode extends AbstractContentNode<BlackboardArtifact> {

    private static final Logger logger = Logger.getLogger(BlackboardArtifactNode.class.getName());

    /*
     * Cache of Content objects used to avoid repeated trips to the case
     * database to retrieve Content objects that are the source of multiple
     * artifacts.
     */
    private static final Cache<Long, Content> contentCache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();

    /*
     * Case application events that affect the property sheets of
     * BlackboardArtifactNodes.
     *
     * NOTE: CURRENT_CASE (closed) events trigger content cache invalidation and
     * unregistering of the PropertyChangeListener subscribed to the application
     * events.
     */
    private static final Set<Case.Events> CASE_EVENTS_OF_INTEREST = EnumSet.of(
            Case.Events.BLACKBOARD_ARTIFACT_TAG_ADDED,
            Case.Events.BLACKBOARD_ARTIFACT_TAG_DELETED,
            Case.Events.CONTENT_TAG_ADDED,
            Case.Events.CONTENT_TAG_DELETED,
            Case.Events.CURRENT_CASE,
            Case.Events.CR_COMMENT_CHANGED);

    /*
     * Artifact types for which the unique path of the artifact's source content
     * should be displayed in the node's property sheet.
     */
    private static final Integer[] SHOW_UNIQUE_PATH = new Integer[]{
        BlackboardArtifact.ARTIFACT_TYPE.TSK_HASHSET_HIT.getTypeID(),
        BlackboardArtifact.ARTIFACT_TYPE.TSK_KEYWORD_HIT.getTypeID(),
        BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT.getTypeID(),
        BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_ARTIFACT_HIT.getTypeID()
    };

    /*
     * Artifact types for which the file metadata of the artifact's source
     * content (a file) should be displayed in the node's property sheet.
     */
    private static final Integer[] SHOW_FILE_METADATA = new Integer[]{
        BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT.getTypeID()
    };

    private final BlackboardArtifact artifact;
    private Content sourceContent;
    private List<NodeProperty<? extends Object>> customProperties;

    /*
     * The node's application event listener.
     */
    private final PropertyChangeListener appEventListener = new PropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            String eventType = evt.getPropertyName();
            if (eventType.equals(Case.Events.BLACKBOARD_ARTIFACT_TAG_ADDED.toString())) {
                BlackBoardArtifactTagAddedEvent event = (BlackBoardArtifactTagAddedEvent) evt;
                if (event.getAddedTag().getArtifact().equals(artifact)) {
                    updateSheet();
                }
            } else if (eventType.equals(Case.Events.BLACKBOARD_ARTIFACT_TAG_DELETED.toString())) {
                BlackBoardArtifactTagDeletedEvent event = (BlackBoardArtifactTagDeletedEvent) evt;
                if (event.getDeletedTagInfo().getArtifactID() == artifact.getArtifactID()) {
                    updateSheet();
                }
            } else if (eventType.equals(Case.Events.CONTENT_TAG_ADDED.toString())) {
                ContentTagAddedEvent event = (ContentTagAddedEvent) evt;
                if (event.getAddedTag().getContent().equals(sourceContent)) {
                    updateSheet();
                }
            } else if (eventType.equals(Case.Events.CONTENT_TAG_DELETED.toString())) {
                ContentTagDeletedEvent event = (ContentTagDeletedEvent) evt;
                if (event.getDeletedTagInfo().getContentID() == sourceContent.getId()) {
                    updateSheet();
                }
            } else if (eventType.equals(Case.Events.CR_COMMENT_CHANGED.toString())) {
                CommentChangedEvent event = (CommentChangedEvent) evt;
                if (event.getContentID() == sourceContent.getId()) {
                    updateSheet();
                }
            } else if (eventType.equals(Case.Events.CURRENT_CASE.toString())) {
                if (evt.getNewValue() == null) {
                    /*
                     * The case has been closed.
                     */
                    unregisterListener();
                    contentCache.invalidateAll();
                }
            } else if (eventType.equals(NodeSpecificEvents.SCO_AVAILABLE.toString()) && !UserPreferences.getHideSCOColumns()) {
                SCOData scoData = (SCOData) evt.getNewValue();
                if (scoData.getScoreAndDescription() != null) {
                    updateSheet(new NodeProperty<>(Bundle.BlackboardArtifactNode_createSheet_score_name(), Bundle.BlackboardArtifactNode_createSheet_score_displayName(), scoData.getScoreAndDescription().getRight(), scoData.getScoreAndDescription().getLeft()));
                }
                if (scoData.getComment() != null) {
                    updateSheet(new NodeProperty<>(Bundle.BlackboardArtifactNode_createSheet_comment_name(), Bundle.BlackboardArtifactNode_createSheet_comment_displayName(), NO_DESCR, scoData.getComment()));
                }
                if (scoData.getCountAndDescription() != null) {
                    updateSheet(new NodeProperty<>(Bundle.BlackboardArtifactNode_createSheet_count_name(), Bundle.BlackboardArtifactNode_createSheet_count_displayName(), scoData.getCountAndDescription().getRight(), scoData.getCountAndDescription().getLeft()));
                }
            }
        }
    };

    /*
     * The node's application event listener is wrapped in a weak reference that
     * allows the node to be garbage collected when the NetBeans infrastructure
     * discards it because the user has navigated to another part of the tree.
     * If this is not done, the strong reference to the listener held by the the
     * event publisher prevents garbage collection of this node.
     *
     * RC: Isn't there some node lifecycle property change event that could be
     * used to unregister the listener?
     */
    private final PropertyChangeListener weakAppEventListener = WeakListeners.propertyChange(appEventListener, null);

    /**
     * Constructs a BlackboardArtifactNode, an AbstractNode implementation that
     * can be used to represent an artifact of any type.
     *
     * @param artifact The artifact to represent.
     * @param iconPath The path to the icon for the artifact type.
     */
    public BlackboardArtifactNode(BlackboardArtifact artifact, String iconPath) {
        super(artifact, createLookup(artifact));
        this.artifact = artifact;
        /*
         * RC: NPE ALERT!! The createLookup method can return a Lookup without
         * the source content in it, so this loop has the potential to leave
         * this.sourceContent null, causing NPEs within this class and in the UI
         * component clients of this class. This constructor should throw
         * instead. However, that would be an exported public API change.
         */
        for (Content lookupContent : this.getLookup().lookupAll(Content.class)) {
            if ((lookupContent != null) && (!(lookupContent instanceof BlackboardArtifact))) {
                sourceContent = lookupContent;
                try {
                    /*
                     * Get the unique path of the source content now (it is
                     * cached in the Content object).
                     */
                    this.sourceContent.getUniquePath();
                } catch (TskCoreException ex) {
                    logger.log(Level.SEVERE, MessageFormat.format("Error getting the unique path of the source content (artifact objID={0}, content objID={1})", artifact.getId(), sourceContent.getId()), ex);
                }
                break;
            }
        }
        setName(Long.toString(artifact.getArtifactID()));
        setDisplayName();
        setIconBaseWithExtension(iconPath);
        Case.addEventTypeSubscriber(CASE_EVENTS_OF_INTEREST, weakAppEventListener);
    }

    /**
     * Constructs a BlackboardArtifactNode, an AbstractNode implementation that
     * can be used to represent an artifact of any type.
     *
     * @param artifact The artifact to represent.
     */
    public BlackboardArtifactNode(BlackboardArtifact artifact) {
        this(artifact, ExtractedContent.getIconFilePath(artifact.getArtifactTypeID()));
    }

    /**
     * Creates a Lookup object for this node and populates it with the artifact
     * this node represents and its source content.
     *
     * @param artifact The artifact this node represents.
     *
     * @return The Lookup.
     */
    private static Lookup createLookup(BlackboardArtifact artifact) {
        final long objectID = artifact.getObjectID();
        try {
            Content content = contentCache.get(objectID, () -> artifact.getSleuthkitCase().getContentById(objectID));
            if (content == null) {
                return Lookups.fixed(artifact);
            } else {
                return Lookups.fixed(artifact, content);
            }
        } catch (ExecutionException ex) {
            logger.log(Level.SEVERE, MessageFormat.format("Error getting source content (artifact objID={0}, content objID={1})", artifact.getId(), objectID), ex); //NON-NLS
            /*
             * RC: NPE ALERT!! The decision to return a Lookup without the
             * source content in it has the potential to cause NPEs within this
             * class and in the UI component clients of this class. The node
             * constructor should throw. However, that would be an exported
             * public API change.
             */
            return Lookups.fixed(artifact);
        }
    }

    /**
     * Unregisters the application event listener when this node is garbage
     * collected, if this finalizer is called.
     *
     * RC: Isn't there some node lifecycle property change event that could be
     * used to unregister the listener?
     *
     * @throws Throwable
     */
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        unregisterListener();
    }

    /**
     * Unregisters this node's application event listener.
     */
    private void unregisterListener() {
        Case.removeEventTypeSubscriber(CASE_EVENTS_OF_INTEREST, weakAppEventListener);
    }

    /**
     * Gets the artifact represented by this node.
     *
     * @return The artifact.
     */
    public BlackboardArtifact getArtifact() {
        return this.artifact;
    }

    @Override
    public Action[] getActions(boolean context) {
        List<Action> actionsList = new ArrayList<>();
        actionsList.addAll(Arrays.asList(super.getActions(context)));

        /*
         * If the artifact represented by this node has a timestamp, add an
         * action to view it in the timeline.
         */
        try {
            if (ViewArtifactInTimelineAction.hasSupportedTimeStamp(artifact)) {
                actionsList.add(new ViewArtifactInTimelineAction(artifact));
            }
        } catch (TskCoreException ex) {
            logger.log(Level.SEVERE, MessageFormat.format("Error getting attributes of artifact (onbjID={0})", artifact.getArtifactID()), ex); //NON-NLS
        }

        /*
         * If the artifact represented by this node is linked to a file via a
         * TSK_PATH_ID attribute, add an action to view the file in the
         * timeline.
         */
        try {
            AbstractFile linkedFile = findLinked(artifact);
            if (linkedFile != null) {
                actionsList.add(ViewFileInTimelineAction.createViewFileAction(linkedFile));
            }
        } catch (TskCoreException ex) {
            logger.log(Level.SEVERE, MessageFormat.format("Error getting linked file of artifact (onbjID={0})", artifact.getArtifactID()), ex); //NON-NLS
        }

        /*
         * If the source content of the artifact represented by this node is a
         * file, add an action to view the file in the data source tree.
         */
        AbstractFile file = getLookup().lookup(AbstractFile.class);
        if (null != file) {
            actionsList.add(ViewFileInTimelineAction.createViewSourceFileAction(file));
        }

        return actionsList.toArray(new Action[actionsList.size()]);
    }

    /**
     * Sets the display name for this node.
     *
     * RC: I am not sure that the naming algorithm in this complicated and
     * potentially fairly costly method has any value whatsoever. This should be
     * investigated. In the mean time, I am preserving all of the comments I
     * found here including: "Set the filter node display name. The value will
     * either be the file name or something along the lines of e.g. "Messages
     * Artifact" for keyword hits on artifacts." Note that this method swallows
     * exceptions without logging them and also has a local variable that hides
     * a superclass field with inappropriate package private access.
     */
    @NbBundle.Messages({"# {0} - artifactDisplayName", "BlackboardArtifactNode.displayName.artifact={0} Artifact"})
    private void setDisplayName() {
        String displayName = ""; //NON-NLS

        // If this is a node for a keyword hit on an artifact, we set the
        // display name to be the artifact type name followed by " Artifact"
        // e.g. "Messages Artifact".
        if (artifact != null
                && (artifact.getArtifactTypeID() == ARTIFACT_TYPE.TSK_KEYWORD_HIT.getTypeID()
                || artifact.getArtifactTypeID() == ARTIFACT_TYPE.TSK_INTERESTING_ARTIFACT_HIT.getTypeID())) {
            try {
                for (BlackboardAttribute attribute : artifact.getAttributes()) {
                    if (attribute.getAttributeType().getTypeID() == ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getTypeID()) {
                        BlackboardArtifact associatedArtifact = Case.getCurrentCaseThrows().getSleuthkitCase().getBlackboardArtifact(attribute.getValueLong());
                        if (associatedArtifact != null) {
                            if (artifact.getArtifactTypeID() == ARTIFACT_TYPE.TSK_INTERESTING_ARTIFACT_HIT.getTypeID()) {
                                artifact.getDisplayName();
                            } else {
                                displayName = NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.displayName.artifact", associatedArtifact.getDisplayName());
                            }
                        }
                    }
                }
            } catch (TskCoreException | NoCurrentCaseException ex) {
                // Do nothing since the display name will be set to the file name.
            }
        }

        if (displayName.isEmpty() && artifact != null) {
            try {
                Content content = Case.getCurrentCaseThrows().getSleuthkitCase().getContentById(artifact.getObjectID());
                displayName = (content == null) ? artifact.getName() : content.getName();
            } catch (TskCoreException | NoCurrentCaseException ex) {
                displayName = artifact.getName();
            }
        }

        this.setDisplayName(displayName);
    }

    /**
     * Gets the name of the source content of the artifact represented by this
     * node.
     *
     * @return The source content name.
     */
    public String getSourceName() {
        return sourceContent.getName();
    }

    @NbBundle.Messages({
        "BlackboardArtifactNode.createSheet.artifactType.displayName=Result Type",
        "BlackboardArtifactNode.createSheet.artifactType.name=Result Type",
        "BlackboardArtifactNode.createSheet.artifactDetails.displayName=Result Details",
        "BlackboardArtifactNode.createSheet.artifactDetails.name=Result Details",
        "BlackboardArtifactNode.createSheet.artifactMD5.displayName=MD5 Hash",
        "BlackboardArtifactNode.createSheet.artifactMD5.name=MD5 Hash",
        "BlackboardArtifactNode.createSheet.fileSize.name=Size",
        "BlackboardArtifactNode.createSheet.fileSize.displayName=Size",
        "BlackboardArtifactNode.createSheet.path.displayName=Path",
        "BlackboardArtifactNode.createSheet.path.name=Path"
    })
    @Override
    protected Sheet createSheet() {
        /*
         * Create an empty property sheet.
         */
        Sheet sheet = super.createSheet();
        Sheet.Set sheetSet = sheet.get(Sheet.PROPERTIES);
        if (sheetSet == null) {
            sheetSet = Sheet.createPropertiesSet();
            sheet.put(sheetSet);
        }

        /*
         * Add the name of the source content of the artifact represented by
         * this node to the sheet.
         */
        sheetSet.put(new NodeProperty<>(NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.srcFile.name"),
                NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.srcFile.displayName"),
                NO_DESCR,
                this.getSourceName()));

        if (!UserPreferences.getHideSCOColumns()) {
            /*
             * Add S(core), C(omments), and O(ther occurences) columns to the
             * sheet and start a background task to compute the value of these
             * properties for the artifact represented by this node. The task
             * will fire a PropertyChangeEvent when the computation is completed
             * and this node's PropertyChangeListener will update the sheet.
             */
            sheetSet.put(new NodeProperty<>(Bundle.BlackboardArtifactNode_createSheet_score_name(), Bundle.BlackboardArtifactNode_createSheet_score_displayName(), VALUE_LOADING, ""));
            sheetSet.put(new NodeProperty<>(Bundle.BlackboardArtifactNode_createSheet_comment_name(), Bundle.BlackboardArtifactNode_createSheet_comment_displayName(), VALUE_LOADING, ""));
            if (CentralRepository.isEnabled()) {
                sheetSet.put(new NodeProperty<>(Bundle.BlackboardArtifactNode_createSheet_count_name(), Bundle.BlackboardArtifactNode_createSheet_count_displayName(), VALUE_LOADING, ""));
            }
            backgroundTasksPool.submit(new GetSCOTask(new WeakReference<>(this), weakAppEventListener));
        }

        /*
         * If the artifact represented by this node is an interesting artifact
         * hit, add the type and description of the interesting artifact to the
         * sheet.
         */
        if (artifact.getArtifactTypeID() == ARTIFACT_TYPE.TSK_INTERESTING_ARTIFACT_HIT.getTypeID()) {
            try {
                BlackboardAttribute attribute = artifact.getAttribute(new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT));
                if (attribute != null) {
                    BlackboardArtifact associatedArtifact = Case.getCurrentCaseThrows().getSleuthkitCase().getBlackboardArtifact(attribute.getValueLong());
                    sheetSet.put(new NodeProperty<>(NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.artifactType.name"),
                            NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.artifactType.displayName"),
                            NO_DESCR,
                            associatedArtifact.getDisplayName()));
                    sheetSet.put(new NodeProperty<>(NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.artifactDetails.name"),
                            NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.artifactDetails.displayName"),
                            NO_DESCR,
                            associatedArtifact.getShortDescription()));
                }
            } catch (TskCoreException | NoCurrentCaseException ex) {
                logger.log(Level.SEVERE, MessageFormat.format("Error getting associated artifact of TSK_INTERESTING_ARTIFACT_HIT artifact (objID={0}))", artifact.getId()), ex); //NON-NLS
            }
        }

        /*
         * Add the attributes of the artifact represented by this node to the
         * sheet.
         */
        Map<String, Object> map = new LinkedHashMap<>();
        fillPropertyMap(map, artifact);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            sheetSet.put(new NodeProperty<>(entry.getKey(),
                    entry.getKey(),
                    NO_DESCR,
                    entry.getValue()));
        }

        /*
         * Add any "custom properties" for the node to the sheet.
         */
        if (customProperties != null) {
            for (NodeProperty<? extends Object> np : customProperties) {
                sheetSet.put(np);
            }
        }

        /*
         * If the artifact represented by this node is a file extension mismatch
         * artifact, add the extension and type of the artifact's source file to
         * the sheet.
         */
        final int artifactTypeId = artifact.getArtifactTypeID();
        if (artifactTypeId == BlackboardArtifact.ARTIFACT_TYPE.TSK_EXT_MISMATCH_DETECTED.getTypeID()) {
            String ext = ""; //NON-NLS
            String actualMimeType = ""; //NON-NLS
            if (sourceContent instanceof AbstractFile) {
                AbstractFile file = (AbstractFile) sourceContent;
                ext = file.getNameExtension();
                actualMimeType = file.getMIMEType();
                if (actualMimeType == null) {
                    actualMimeType = ""; //NON-NLS
                }
            }
            sheetSet.put(new NodeProperty<>(NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.ext.name"),
                    NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.ext.displayName"),
                    NO_DESCR,
                    ext));
            sheetSet.put(new NodeProperty<>(
                    NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.mimeType.name"),
                    NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.mimeType.displayName"),
                    NO_DESCR,
                    actualMimeType));
        }

        /*
         * If the type of the artifact represented by this node dictates the
         * addition of the source content's unique path, add it to the sheet.
         */
        if (Arrays.asList(SHOW_UNIQUE_PATH).contains(artifactTypeId)) {
            String sourcePath = ""; //NON-NLS
            try {
                sourcePath = sourceContent.getUniquePath();
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, MessageFormat.format("Error getting unique path of source content (artifact objID={0}, content objID={1})", artifact.getId(), sourceContent.getId()), ex); //NON-NLS
            }

            if (sourcePath.isEmpty() == false) {
                sheetSet.put(new NodeProperty<>(
                        NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.filePath.name"),
                        NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.filePath.displayName"),
                        NO_DESCR,
                        sourcePath));
            }

            /*
             * If the type of the artifact represented by this node dictates the
             * addition of the source content's file metadata, add it to the
             * sheet. Otherwise, add the data source to the sheet.
             */
            if (Arrays.asList(SHOW_FILE_METADATA).contains(artifactTypeId)) {
                AbstractFile file = sourceContent instanceof AbstractFile ? (AbstractFile) sourceContent : null;
                sheetSet.put(new NodeProperty<>(NbBundle.getMessage(BlackboardArtifactNode.class, "ContentTagNode.createSheet.fileModifiedTime.name"),
                        NbBundle.getMessage(BlackboardArtifactNode.class, "ContentTagNode.createSheet.fileModifiedTime.displayName"),
                        "",
                        file == null ? "" : ContentUtils.getStringTime(file.getMtime(), file)));
                sheetSet.put(new NodeProperty<>(NbBundle.getMessage(BlackboardArtifactNode.class, "ContentTagNode.createSheet.fileChangedTime.name"),
                        NbBundle.getMessage(BlackboardArtifactNode.class, "ContentTagNode.createSheet.fileChangedTime.displayName"),
                        "",
                        file == null ? "" : ContentUtils.getStringTime(file.getCtime(), file)));
                sheetSet.put(new NodeProperty<>(NbBundle.getMessage(BlackboardArtifactNode.class, "ContentTagNode.createSheet.fileAccessedTime.name"),
                        NbBundle.getMessage(BlackboardArtifactNode.class, "ContentTagNode.createSheet.fileAccessedTime.displayName"),
                        "",
                        file == null ? "" : ContentUtils.getStringTime(file.getAtime(), file)));
                sheetSet.put(new NodeProperty<>(NbBundle.getMessage(BlackboardArtifactNode.class, "ContentTagNode.createSheet.fileCreatedTime.name"),
                        NbBundle.getMessage(BlackboardArtifactNode.class, "ContentTagNode.createSheet.fileCreatedTime.displayName"),
                        "",
                        file == null ? "" : ContentUtils.getStringTime(file.getCrtime(), file)));
                sheetSet.put(new NodeProperty<>(NbBundle.getMessage(BlackboardArtifactNode.class, "ContentTagNode.createSheet.fileSize.name"),
                        NbBundle.getMessage(BlackboardArtifactNode.class, "ContentTagNode.createSheet.fileSize.displayName"),
                        "",
                        sourceContent.getSize()));
                sheetSet.put(new NodeProperty<>(Bundle.BlackboardArtifactNode_createSheet_artifactMD5_name(),
                        Bundle.BlackboardArtifactNode_createSheet_artifactMD5_displayName(),
                        "",
                        file == null ? "" : StringUtils.defaultString(file.getMd5Hash())));
            }
        } else {
            String dataSourceStr = "";
            try {
                Content dataSource = sourceContent.getDataSource();
                if (dataSource != null) {
                    dataSourceStr = dataSource.getName();
                } else {
                    dataSourceStr = getRootAncestorName();
                }
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, MessageFormat.format("Error getting source data soiurce name (artifact objID={0}, content objID={1})", artifact.getId(), sourceContent.getId()), ex); //NON-NLS
            }

            if (dataSourceStr.isEmpty() == false) {
                sheetSet.put(new NodeProperty<>(
                        NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.dataSrc.name"),
                        NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.dataSrc.displayName"),
                        NO_DESCR,
                        dataSourceStr));
            }
        }

        /*
         * If the artifact represented by this node is an EXIF artifact, add the
         * source file size and path to the sheet.
         */
        if (artifactTypeId == BlackboardArtifact.ARTIFACT_TYPE.TSK_METADATA_EXIF.getTypeID()) {
            long size = 0;
            String path = ""; //NON-NLS
            if (sourceContent instanceof AbstractFile) {
                AbstractFile af = (AbstractFile) sourceContent;
                size = af.getSize();
                try {
                    path = af.getUniquePath();
                } catch (TskCoreException ex) {
                    path = af.getParentPath();
                }
            }
            sheetSet.put(new NodeProperty<>(NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.fileSize.name"),
                    NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.fileSize.displayName"),
                    NO_DESCR,
                    size));
            sheetSet.put(new NodeProperty<>(
                    NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.path.name"),
                    NbBundle.getMessage(BlackboardArtifactNode.class, "BlackboardArtifactNode.createSheet.path.displayName"),
                    NO_DESCR,
                    path));
        }

        return sheet;
    }

    /**
     * Gets all of the tags applied to the artifact represented by this node and
     * its source content.
     *
     * @return The tags.
     */
    @Override
    protected final List<Tag> getAllTagsFromDatabase() {
        List<Tag> tags = new ArrayList<>();
        try {
            tags.addAll(Case.getCurrentCaseThrows().getServices().getTagsManager().getBlackboardArtifactTagsByArtifact(artifact));
            tags.addAll(Case.getCurrentCaseThrows().getServices().getTagsManager().getContentTagsByContent(sourceContent));
        } catch (TskCoreException | NoCurrentCaseException ex) {
            logger.log(Level.SEVERE, MessageFormat.format("Error getting tags for artifact and its source content (artifact objID={0}, content objID={1})", artifact.getId(), sourceContent.getId()), ex);
        }
        return tags;
    }

    /**
     * Gets the correlation attribute for the MD5 hash of the source file of the
     * artifact represented by this node. The correlation attribute instance can
     * only be returned if the central repository is enabled and the source
     * content is a file.
     *
     * @return The correlation attribute instance, may be null.
     */
    @Override
    protected final CorrelationAttributeInstance getCorrelationAttributeInstance() {
        CorrelationAttributeInstance correlationAttribute = null;
        if (CentralRepository.isEnabled() && sourceContent instanceof AbstractFile) {
            correlationAttribute = CorrelationAttributeUtil.getCorrAttrForFile((AbstractFile) sourceContent);
        }
        return correlationAttribute;
    }

    /**
     * Computes the value of the comment property ("C" in S, C, O) for the
     * artifact represented by this node. An icon is displayed if a commented
     * tag has been applied to the artifact or its source content, or if there
     * is a comment on the MD5 hash of the source file in the central
     * repository.
     *
     * @param tags      The tags applied to the artifact and its source content.
     * @param attribute The correlation attribute for the MD5 hash of the source
     *                  file of the artifact, may be null.
     *
     * @return The value of the comment property.
     */
    @Override
    protected DataResultViewerTable.HasCommentStatus getCommentProperty(List<Tag> tags, CorrelationAttributeInstance attribute) {

        HasCommentStatus status = tags.size() > 0 ? HasCommentStatus.TAG_NO_COMMENT : HasCommentStatus.NO_COMMENT;
        for (Tag tag : tags) {
            if (!StringUtils.isBlank(tag.getComment())) {
                status = HasCommentStatus.TAG_COMMENT;
                break;
            }
        }

        if (attribute != null && !StringUtils.isBlank(attribute.getComment())) {
            if (status == HasCommentStatus.TAG_COMMENT) {
                status = HasCommentStatus.CR_AND_TAG_COMMENTS;
            } else {
                status = HasCommentStatus.CR_COMMENT;
            }
        }

        return status;
    }

    /**
     * Computes the value of the score property ("S" in S, C, O) for the
     * artifact represented by this node. The score property indicates whether
     * the artifact or its source content is interesting or notable. A red icon
     * will be displayed if the hash of the source file has been found in a
     * notable hash set or if either the artifact or its source content has been
     * tagged with a notable tag. A yellow icon will be displayed if the source
     * file belongs to an interesting file set or either the artifact or its
     * source content has been tagged with a non-notable tag.
     *
     * @param tags The tags that have been applied to the artifact and its
     *             source content.
     *
     * @return The value of the score property as an enum element and a
     *         description string for dislpay in a tool tip.
     */
    @Override
    protected Pair<DataResultViewerTable.Score, String> getScorePropertyAndDescription(List<Tag> tags) {
        Score score = Score.NO_SCORE;
        String description = Bundle.BlackboardArtifactNode_createSheet_noScore_description();
        if (sourceContent instanceof AbstractFile) {
            if (((AbstractFile) sourceContent).getKnown() == TskData.FileKnown.BAD) {
                score = Score.NOTABLE_SCORE;
                description = Bundle.BlackboardArtifactNode_createSheet_notableFile_description();
            }
        }
        //if the artifact being viewed is a hashhit check if the hashset is notable 
        if ((score == Score.NO_SCORE || score == Score.INTERESTING_SCORE) && content.getArtifactTypeID() == ARTIFACT_TYPE.TSK_HASHSET_HIT.getTypeID()) {
            try {
                BlackboardAttribute attr = content.getAttribute(new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_SET_NAME));
                List<HashDbManager.HashDb> notableHashsets = HashDbManager.getInstance().getKnownBadFileHashSets();
                for (HashDbManager.HashDb hashDb : notableHashsets) {
                    if (hashDb.getHashSetName().equals(attr.getValueString())) {
                        score = Score.NOTABLE_SCORE;
                        description = Bundle.BlackboardArtifactNode_createSheet_notableFile_description();
                        break;
                    }
                }
            } catch (TskCoreException ex) {
                //unable to get the attribute so we can not update the status based on the attribute                
                logger.log(Level.SEVERE, MessageFormat.format("Error getting TSK_SET_NAME attribute (artifact objID={0}, content objID={1})", artifact.getId(), sourceContent.getId()), ex);
            }
        }
        try {
            if (score == Score.NO_SCORE && !content.getArtifacts(BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_ARTIFACT_HIT).isEmpty()) {
                score = Score.INTERESTING_SCORE;
                description = Bundle.BlackboardArtifactNode_createSheet_interestingResult_description();
            }
        } catch (TskCoreException ex) {
            logger.log(Level.SEVERE, MessageFormat.format("Error getting TSK_INTERESTING_ARTIFACT_HIT artifacts (artifact objID={0}, content objID={1})", artifact.getId(), sourceContent.getId()), ex);
        }
        if (tags.size() > 0 && (score == Score.NO_SCORE || score == Score.INTERESTING_SCORE)) {
            score = Score.INTERESTING_SCORE;
            description = Bundle.BlackboardArtifactNode_createSheet_taggedItem_description();
            for (Tag tag : tags) {
                if (tag.getName().getKnownStatus() == TskData.FileKnown.BAD) {
                    score = Score.NOTABLE_SCORE;
                    description = Bundle.BlackboardArtifactNode_createSheet_notableTaggedItem_description();
                    break;
                }
            }
        }

        return Pair.of(score, description);
    }

    /**
     * Computes the value of the other occurrences property ("O" in S, C, O) for
     * the artifact represented by this node. The value of the other occurrences
     * property is the number of other data sources this artifact appears in
     * according to a correlation attribute instance lookup in the central
     * repository, plus one for the data source for this instance of the
     * artifact.
     *
     * @param corrAttrType       The correlation attribute instance type to use
     *                           for the central repsoitory lookup.
     * @param attributeValue     The correlation attribute instane value to use
     *                           for the central repsoitory lookup.
     * @param defaultDescription A default description.
     *
     * @return The value of the occurrences property as a data sources count and
     *         a description string.
     *
     */
    @Override
    protected Pair<Long, String> getCountPropertyAndDescription(Type corrAttrType, String attributeValue, String defaultDescription) {
        Long count = -1L;
        String description = defaultDescription;
        try {
            //don't perform the query if there is no correlation value
            if (corrAttrType != null && StringUtils.isNotBlank(attributeValue)) {
                count = CentralRepository.getInstance().getCountUniqueCaseDataSourceTuplesHavingTypeValue(corrAttrType, attributeValue);
                description = Bundle.BlackboardArtifactNode_createSheet_count_description(count, corrAttrType.getDisplayName());
            } else if (corrAttrType != null) {
                description = Bundle.BlackboardArtifactNode_createSheet_count_noCorrelationValues_description();
            }
        } catch (CentralRepoException ex) {
            logger.log(Level.SEVERE, MessageFormat.format("Error querying central repository for other occurences count (artifact objID={0}, content objID={1}, corrAttrType={2}, corrAttrValue={3})", artifact.getId(), sourceContent.getId(), corrAttrType, attributeValue), ex);
        } catch (CorrelationAttributeNormalizationException ex) {
            logger.log(Level.SEVERE, MessageFormat.format("Error normalizing correlation attribute for central repository query (artifact objID={0}, content objID={1}, corrAttrType={2}, corrAttrValue={3})", artifact.getId(), sourceContent.getId(), corrAttrType, attributeValue), ex);
        }
        return Pair.of(count, description);
    }

    /**
     * Refreshes this node's property sheet.
     */
    private void updateSheet() {
        this.setSheet(createSheet());
    }

    /**
     * Gets the name of the root ancestor of the source content for the artifact
     * represented by this node.
     *
     * @return The root ancestor name or the empty string if an error occurs.
     */
    private String getRootAncestorName() {
        String parentName = sourceContent.getName();
        Content parent = sourceContent;
        try {
            while ((parent = parent.getParent()) != null) {
                parentName = parent.getName();
            }
        } catch (TskCoreException ex) {
            logger.log(Level.SEVERE, MessageFormat.format("Error getting root ancestor name for source content (artifact objID={0}, content objID={1})", artifact.getId(), sourceContent.getId())); //NON-NLS
            return "";
        }
        return parentName;
    }

    /**
     * Adds a "custom" property to the property sheet of this node.
     *
     * @param property The custom property.
     */
    public void addNodeProperty(NodeProperty<?> property) {
        if (customProperties == null) {
            customProperties = new ArrayList<>();
        }
        customProperties.add(property);
    }

    /**
     * Converts the attributes of the artifact this node represents to a map of
     * name-value pairs, where the names are attribute type display names.
     *
     * @param map      The map to be populated with the artifact attribute
     *                 name-value pairs.
     * @param artifact The artifact.
     */
    @SuppressWarnings("deprecation")
    private void fillPropertyMap(Map<String, Object> map, BlackboardArtifact artifact) {
        try {
            for (BlackboardAttribute attribute : artifact.getAttributes()) {
                final int attributeTypeID = attribute.getAttributeType().getTypeID();
                //skip some internal attributes that user shouldn't see
                if (attributeTypeID == ATTRIBUTE_TYPE.TSK_PATH_ID.getTypeID()
                        || attributeTypeID == ATTRIBUTE_TYPE.TSK_TAGGED_ARTIFACT.getTypeID()
                        || attributeTypeID == ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getTypeID()
                        || attributeTypeID == ATTRIBUTE_TYPE.TSK_SET_NAME.getTypeID()
                        || attributeTypeID == ATTRIBUTE_TYPE.TSK_KEYWORD_SEARCH_TYPE.getTypeID()
                        || attribute.getValueType() == BlackboardAttribute.TSK_BLACKBOARD_ATTRIBUTE_VALUE_TYPE.JSON) {
                    /*
                     * Do nothing.
                     */
                } else if (artifact.getArtifactTypeID() == BlackboardArtifact.ARTIFACT_TYPE.TSK_EMAIL_MSG.getTypeID()) {
                    addEmailMsgProperty(map, attribute);
                } else if (attribute.getAttributeType().getValueType() == BlackboardAttribute.TSK_BLACKBOARD_ATTRIBUTE_VALUE_TYPE.DATETIME) {
                    map.put(attribute.getAttributeType().getDisplayName(), ContentUtils.getStringTime(attribute.getValueLong(), sourceContent));
                } else if (artifact.getArtifactTypeID() == ARTIFACT_TYPE.TSK_TOOL_OUTPUT.getTypeID()
                        && attributeTypeID == ATTRIBUTE_TYPE.TSK_TEXT.getTypeID()) {
                    /*
                     * The truncation of text attributes appears to have been
                     * motivated by the statement that "RegRipper output would
                     * often cause the UI to get a black line accross it and
                     * hang if you hovered over large output or selected it.
                     * This reduces the amount of data in the table. Could
                     * consider doing this for all fields in the UI."
                     */
                    String value = attribute.getDisplayString();
                    if (value.length() > 512) {
                        value = value.substring(0, 512);
                    }
                    map.put(attribute.getAttributeType().getDisplayName(), value);
                } else {
                    map.put(attribute.getAttributeType().getDisplayName(), attribute.getDisplayString());
                }
            }
        } catch (TskCoreException ex) {
            logger.log(Level.SEVERE, MessageFormat.format("Error getting artifact attributes (artifact objID={0}, content objID={1})", artifact.getId(), sourceContent.getId()), ex); //NON-NLS
        }
    }

    /**
     * Adds an email message attribute of the artifact this node represents to a
     * map of name-value pairs, where the names are attribute type display
     * names.
     *
     * @param map       The map to be populated with the artifact attribute
     *                  name-value pair.
     * @param attribute The attribute to use to make the map entry.
     */
    private void addEmailMsgProperty(Map<String, Object> map, BlackboardAttribute attribute) {

        final int attributeTypeID = attribute.getAttributeType().getTypeID();

        // Skip certain Email msg attributes
        if (attributeTypeID == ATTRIBUTE_TYPE.TSK_DATETIME_SENT.getTypeID()
                || attributeTypeID == ATTRIBUTE_TYPE.TSK_EMAIL_CONTENT_HTML.getTypeID()
                || attributeTypeID == ATTRIBUTE_TYPE.TSK_EMAIL_CONTENT_RTF.getTypeID()
                || attributeTypeID == ATTRIBUTE_TYPE.TSK_EMAIL_BCC.getTypeID()
                || attributeTypeID == ATTRIBUTE_TYPE.TSK_EMAIL_CC.getTypeID()
                || attributeTypeID == ATTRIBUTE_TYPE.TSK_HEADERS.getTypeID()) {
            /*
             * Do nothing.
             */
        } else if (attributeTypeID == ATTRIBUTE_TYPE.TSK_EMAIL_CONTENT_PLAIN.getTypeID()) {
            String value = attribute.getDisplayString();
            if (value.length() > 160) {
                value = value.substring(0, 160) + "...";
            }
            map.put(attribute.getAttributeType().getDisplayName(), value);
        } else if (attribute.getAttributeType().getValueType() == BlackboardAttribute.TSK_BLACKBOARD_ATTRIBUTE_VALUE_TYPE.DATETIME) {
            map.put(attribute.getAttributeType().getDisplayName(), ContentUtils.getStringTime(attribute.getValueLong(), sourceContent));
        } else {
            map.put(attribute.getAttributeType().getDisplayName(), attribute.getDisplayString());
        }

    }

    @Override
    public <T> T accept(DisplayableItemNodeVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean isLeafTypeNode() {
        return true;
    }

    @Override
    public String getItemType() {
        return getClass().getName();
    }

    @Override
    public <T> T accept(ContentNodeVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /**
     * Adds the score property for the artifact represented by this node to the
     * node property sheet.
     *
     * @param sheetSet The property sheet.
     * @param tags     The tags that have been applied to the artifact and its
     *                 source content.
     *
     * @deprecated Do not use. The score property is now computed in a
     * background thread and added to the property sheet in this node's event
     * PropertyChangeEventListner.
     */
    @NbBundle.Messages({"BlackboardArtifactNode.createSheet.score.name=S",
        "BlackboardArtifactNode.createSheet.score.displayName=S",
        "BlackboardArtifactNode.createSheet.notableFile.description=Associated file recognized as notable.",
        "BlackboardArtifactNode.createSheet.interestingResult.description=Result has an interesting result associated with it.",
        "BlackboardArtifactNode.createSheet.taggedItem.description=Result or associated file has been tagged.",
        "BlackboardArtifactNode.createSheet.notableTaggedItem.description=Result or associated file tagged with notable tag.",
        "BlackboardArtifactNode.createSheet.noScore.description=No score"})
    @Deprecated
    protected final void addScorePropertyAndDescription(Sheet.Set sheetSet, List<Tag> tags) {
        Pair<DataResultViewerTable.Score, String> scoreAndDescription = getScorePropertyAndDescription(tags);
        sheetSet.put(new NodeProperty<>(Bundle.BlackboardArtifactNode_createSheet_score_name(), Bundle.BlackboardArtifactNode_createSheet_score_displayName(), scoreAndDescription.getRight(), scoreAndDescription.getLeft()));
    }

    /**
     * Adds the tags property for the artifact represented by this node to the
     * node property sheet.
     *
     * @param sheetSet The property sheet.
     *
     * @deprecated Do not use. The tags property is now computed in a background
     * thread and added to the property sheet in this node's event
     * PropertyChangeEventListner.
     */
    @NbBundle.Messages({
        "BlackboardArtifactNode.createSheet.tags.displayName=Tags"}
    )
    @Deprecated
    protected void addTagProperty(Sheet.Set sheetSet) throws MissingResourceException {
        List<Tag> tags = new ArrayList<>();
        try {
            tags.addAll(Case.getCurrentCaseThrows().getServices().getTagsManager().getBlackboardArtifactTagsByArtifact(artifact));
            tags.addAll(Case.getCurrentCaseThrows().getServices().getTagsManager().getContentTagsByContent(sourceContent));
        } catch (TskCoreException | NoCurrentCaseException ex) {
            logger.log(Level.SEVERE, MessageFormat.format("Error getting tags for artifact and source content (artifact objID={0}, content objID={1})", artifact.getId(), sourceContent.getId()), ex);
        }
        sheetSet.put(new NodeProperty<>("Tags", Bundle.BlackboardArtifactNode_createSheet_tags_displayName(), NO_DESCR, tags.stream().map(t -> t.getName().getDisplayName()).collect(Collectors.joining(", "))));
    }

    /**
     * Adds the tags property for the artifact represented by this node to the
     * node property sheet.
     *
     * @param sheetSet The property sheet.
     * @param tags     The tags that have been applied to the artifact and its
     *                 source content.
     *
     * @deprecated Do not use. The tags property is now computed in a background
     * thread and added to the property sheet in this node's event
     * PropertyChangeEventListner.
     */
    @Deprecated
    protected final void addTagProperty(Sheet.Set sheetSet, List<Tag> tags) {
        sheetSet.put(new NodeProperty<>("Tags", Bundle.BlackboardArtifactNode_createSheet_tags_displayName(), NO_DESCR, tags.stream().map(t -> t.getName().getDisplayName()).collect(Collectors.joining(", "))));
    }

    /**
     * Adds the count property for the artifact represented by this node to the
     * node property sheet.
     *
     * @param sheetSet  The property sheet.
     * @param attribute The correlation attribute instance to use for the
     *                  central repository lookup.
     *
     * @deprecated Do not use. The count property is now computed in a
     * background thread and added to the property sheet in this node's event
     * PropertyChangeEventListner.
     */
    @NbBundle.Messages({"BlackboardArtifactNode.createSheet.count.name=O",
        "BlackboardArtifactNode.createSheet.count.displayName=O",
        "BlackboardArtifactNode.createSheet.count.noCorrelationAttributes.description=No correlation properties found",
        "BlackboardArtifactNode.createSheet.count.noCorrelationValues.description=Unable to find other occurrences because no value exists for the available correlation property",
        "# {0} - occurrenceCount",
        "# {1} - attributeType",
        "BlackboardArtifactNode.createSheet.count.description=There were {0} datasource(s) found with occurrences of the correlation value of type {1}"})
    @Deprecated
    protected final void addCountProperty(Sheet.Set sheetSet, CorrelationAttributeInstance attribute) {
        Pair<Long, String> countAndDescription = getCountPropertyAndDescription(attribute.getCorrelationType(), attribute.getCorrelationValue(), Bundle.BlackboardArtifactNode_createSheet_count_noCorrelationAttributes_description());
        sheetSet.put(new NodeProperty<>(Bundle.BlackboardArtifactNode_createSheet_count_name(), Bundle.BlackboardArtifactNode_createSheet_count_displayName(), countAndDescription.getRight(), countAndDescription.getLeft()));
    }

    /**
     * Adds the other occurrences property for the artifact represented by this
     * node to the node property sheet.
     *
     * @param sheetSet  The property sheet.
     * @param tags      The tags that have been applied to the artifact and its
     *                  source content.
     * @param attribute The correlation attribute instance to use for the
     *                  central repository lookup.
     *
     * @deprecated Do not use. The count property is now computed in a
     * background thread and added to the property sheet in this node's event
     * PropertyChangeEventListner.
     */
    @NbBundle.Messages({"BlackboardArtifactNode.createSheet.comment.name=C",
        "BlackboardArtifactNode.createSheet.comment.displayName=C"})
    @Deprecated
    protected final void addCommentProperty(Sheet.Set sheetSet, List<Tag> tags, CorrelationAttributeInstance attribute) {
        HasCommentStatus status = getCommentProperty(tags, attribute);
        sheetSet.put(new NodeProperty<>(Bundle.BlackboardArtifactNode_createSheet_comment_name(), Bundle.BlackboardArtifactNode_createSheet_comment_displayName(), NO_DESCR, status));
    }

}
