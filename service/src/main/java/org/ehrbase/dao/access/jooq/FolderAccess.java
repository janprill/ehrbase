/*
 * Copyright (c) 2019 Vitasystems GmbH, Hannover Medical School, and Luis Marco-Ruiz (Hannover Medical School).
 *
 * This file is part of project EHRbase
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

package org.ehrbase.dao.access.jooq;

import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.directory.Folder;
import com.nedap.archie.rm.support.identification.ObjectId;
import com.nedap.archie.rm.support.identification.ObjectRef;
import com.nedap.archie.rm.support.identification.ObjectVersionId;
import com.nedap.archie.rm.support.identification.UIDBasedId;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ehrbase.api.exception.ObjectNotFoundException;
import org.ehrbase.dao.access.interfaces.I_ConceptAccess;
import org.ehrbase.dao.access.interfaces.I_ContributionAccess;
import org.ehrbase.dao.access.interfaces.I_DomainAccess;
import org.ehrbase.dao.access.interfaces.I_FolderAccess;
import org.ehrbase.dao.access.support.DataAccess;
import org.ehrbase.dao.access.util.ContributionDef;
import org.ehrbase.dao.access.util.FolderUtils;
import org.ehrbase.jooq.binding.OtherDetailsJsonbBinder;
import org.ehrbase.jooq.binding.SysPeriodBinder;
import org.ehrbase.jooq.pg.Routines;
import org.ehrbase.jooq.pg.enums.ContributionDataType;
import org.ehrbase.jooq.pg.tables.AdminDeleteFolderHistory;
import org.ehrbase.jooq.pg.tables.AdminDeleteFolderObjRefHistory;
import org.ehrbase.jooq.pg.tables.FolderHierarchy;
import org.ehrbase.jooq.pg.tables.records.*;
import org.joda.time.DateTime;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Record13;
import org.jooq.Record8;
import org.jooq.Result;
import org.jooq.Table;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

import static org.ehrbase.jooq.pg.Tables.CONTRIBUTION;
import static org.ehrbase.jooq.pg.Tables.FOLDER;
import static org.ehrbase.jooq.pg.Tables.FOLDER_HIERARCHY;
import static org.ehrbase.jooq.pg.Tables.FOLDER_HISTORY;
import static org.ehrbase.jooq.pg.Tables.FOLDER_ITEMS;
import static org.ehrbase.jooq.pg.Tables.OBJECT_REF;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;

/***
 *@Created by Luis Marco-Ruiz on Jun 13, 2019
 */
public class FolderAccess extends DataAccess implements I_FolderAccess, Comparable<FolderAccess> {

    private static final Logger log = LogManager.getLogger(FolderAccess.class);

    // TODO: Check how to remove this unused details for confusion prevention
    private ItemStructure details;

    private List<ObjectRef> items = new ArrayList<>();
    private Map<UUID, I_FolderAccess> subfoldersList = new TreeMap<>();
    private I_ContributionAccess contributionAccess;
    private UUID ehrId;
    private FolderRecord folderRecord;

    /********Constructors*******/

    public FolderAccess(I_DomainAccess domainAccess) {
        super(domainAccess);
        this.folderRecord = getContext().newRecord(org.ehrbase.jooq.pg.tables.Folder.FOLDER);

        //associate a contribution with this composition
        this.contributionAccess = I_ContributionAccess.getInstance(this, this.ehrId);
        this.contributionAccess.setState(ContributionDef.ContributionState.COMPLETE);
    }

    public FolderAccess(I_DomainAccess domainAccess, UUID ehrId, I_ContributionAccess contributionAccess) {
        super(domainAccess);
        this.ehrId = ehrId;
        this.folderRecord = getContext().newRecord(org.ehrbase.jooq.pg.tables.Folder.FOLDER);
        this.contributionAccess = contributionAccess;
        //associate a contribution with this composition, if needed.
        if (contributionAccess == null) {
            this.contributionAccess = I_ContributionAccess.getInstance(this, this.ehrId);
        }
        UUID ehrIdLoc = this.contributionAccess.getEhrId();
        this.contributionAccess.setState(ContributionDef.ContributionState.COMPLETE);
    }

    /*************Data Access and modification methods*****************/

    @Override
    public Boolean update(Timestamp transactionTime) {
        return this.update(transactionTime, true);
    }

    @Override
    public Boolean update(final Timestamp transactionTime, final boolean force) {
        /*create new contribution*/
        UUID old_contribution = this.folderRecord.getInContribution();
        UUID new_contribution = this.folderRecord.getInContribution();

        UUID ehrId = this.contributionAccess.getEhrId();
        /*save the EHR id from old_contribution since it will be the same as this is an update operation*/
        if (this.contributionAccess.getEhrId() == null) {
            final Record1<UUID> result1 = getContext().select(CONTRIBUTION.EHR_ID).from(CONTRIBUTION).where(CONTRIBUTION.ID.eq(old_contribution)).fetch().get(0);
            ehrId = result1.value1();
        }
        this.contributionAccess.setEhrId(ehrId);

        this.contributionAccess.commit(transactionTime, null, null, ContributionDataType.folder, ContributionDef.ContributionState.COMPLETE, I_ConceptAccess.ContributionChangeType.MODIFICATION, null);
        this.getFolderRecord().setInContribution(this.contributionAccess.getId());
        new_contribution = folderRecord.getInContribution();

        // Delete so folder can be overwritten
        // This will also delete items since cascading the delete to the items table as well as
        // all FolderHierarchy entires
        this.delete(folderRecord.getId());

        return this.update(transactionTime, true, true, null, old_contribution, new_contribution);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectVersionId create() {
        return new ObjectVersionId(
                this.commit().toString()
                + "::" + getServerConfig().getNodename()
                + "::1"
        );
    }

    private Boolean update(final Timestamp transactionTime,
                           final boolean force,
                           boolean rootFolder,
                           UUID parentFolder,
                           UUID oldContribution,
                           UUID newContribution) {

        boolean result = false;

        DSLContext dslContext = getContext();
        dslContext.attach(this.folderRecord);

        // Set new Contribution for MODIFY
        this.setInContribution(newContribution);

        // Copy into new instance and attach to DB context.
        FolderRecord updatedFolderRecord = new FolderRecord();

        if(rootFolder) {//if it is the root folder preserve the original id, itherwise let the DB provide a new one for the overriden subfolders.

            updatedFolderRecord.setId(this.getFolderId());
        }
        updatedFolderRecord.setInContribution(newContribution);
        updatedFolderRecord.setName(this.getFolderName());
        updatedFolderRecord.setArchetypeNodeId(this.getFolderArchetypeNodeId());
        updatedFolderRecord.setActive(this.isFolderActive());
        updatedFolderRecord.setDetails(this.getFolderDetails());
        updatedFolderRecord.setSysTransaction(transactionTime);
        updatedFolderRecord.setSysPeriod(this.getFolderSysPeriod());

        // attach to context DB
        dslContext.attach(updatedFolderRecord);

        // Save new Folder entry to the database
        result = updatedFolderRecord.store() > 0;
        // Get new folder id for folder items and hierarchy
        UUID updatedFolderId = updatedFolderRecord.getId();

        // Update items -> Save new list of all items in this folder
        this.saveFolderItems(updatedFolderId,
                oldContribution,
                newContribution,
                transactionTime,
                getContext());

        // Create FolderHierarchy entries if this instance is a sub folder
        if (!rootFolder) {
            FolderHierarchyRecord updatedFhR = new FolderHierarchyRecord();
            updatedFhR.setParentFolder(parentFolder);
            updatedFhR.setChildFolder(updatedFolderId);
            updatedFhR.setInContribution(newContribution);
            updatedFhR.setSysTransaction(transactionTime);
            updatedFhR.setSysPeriod(folderRecord.getSysPeriod());
            dslContext.attach(updatedFhR);
            updatedFhR.store();
        }

        boolean anySubfolderModified = this.getSubfoldersList() // Map of sub folders with UUID
                .values() // Get all I_FolderAccess entries
                .stream() // Iterate over the I_FolderAccess entries
                .map(subfolder -> ( // Update each entry and return if there has been at least one entry updated
                        ((FolderAccess) subfolder).update(transactionTime,
                                force,
                                false,
                                updatedFolderId,
                                oldContribution,
                                newContribution)
                )).reduce((b1, b2) -> b1 || b2).orElse(false);

        // Finally overwrite original FolderRecord on this FolderAccess instance to have the
        // new data available at service layer. Thus we do not need to re-fetch the updated folder
        // tree from DB
        this.folderRecord = updatedFolderRecord;
        return result || anySubfolderModified;
    }

    private void saveFolderItems(final UUID folderId, final UUID old_contribution, final UUID new_contribution, final Timestamp transactionTime, DSLContext context) {

        for (ObjectRef or : this.getItems()) {

            //insert in object_ref
            ObjectRefRecord orr = new ObjectRefRecord(or.getNamespace(), or.getType(), UUID.fromString(or.getId().getValue()), new_contribution, transactionTime, folderRecord.getSysPeriod());
            context.attach(orr);
            orr.store();

            //insert in folder_item
            FolderItemsRecord fir = new FolderItemsRecord(folderId, UUID.fromString(or.getId().getValue()), new_contribution, transactionTime, folderRecord.getSysPeriod());
            context.attach(fir);
            fir.store();
        }
    }

    @Override
    public Boolean update() {
        return this.update(Timestamp.from(Instant.now()), true);
    }

    @Override
    public Boolean update(Boolean force) {
        return this.update(Timestamp.from(Instant.now()), force);
    }

    @Override
    public Integer delete() {
        return this.delete(this.getFolderId());
    }

    @Override
    public UUID commit() {
        Timestamp timestamp = Timestamp.from(Instant.now());
        return this.commit(timestamp);
    }

    @Override
    public UUID commit(Timestamp transactionTime) {
        // Create Contribution entry for all folders
        this.contributionAccess.commit(
                transactionTime,
                null,
                null,
                ContributionDataType.folder,
                ContributionDef.ContributionState.COMPLETE,
                I_ConceptAccess.ContributionChangeType.CREATION,
                null
        );

        return this.commit(transactionTime, this.contributionAccess.getContributionId());

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UUID commit(Timestamp transactionTime, UUID contributionId) {

        this.getFolderRecord().setInContribution(contributionId);

        // Save the folder record to database
        this.getFolderRecord().store();

        //Save folder items
        this.saveFolderItems(this.getFolderRecord().getId(), contributionId, contributionId, transactionTime, getContext());

        // Save list of sub folders to database with parent <-> child ID relations
        this.getSubfoldersList().values().forEach(child -> {
            child.commit(transactionTime, contributionId);
            FolderHierarchyRecord fhRecord = this.buildFolderHierarchyRecord(
                    this.getFolderRecord().getId(),
                    ((FolderAccess) child).getFolderRecord().getId(),
                    contributionId,
                    transactionTime,
                    null
            );
            fhRecord.store();
        });
        return this.getFolderRecord().getId();

    }

    /**
     * Retrieve instance of {@link I_FolderAccess} with the information needed retrieve the folder and its sub-folders.
     *
     * @param domainAccess providing the information about the DB connection.
     * @param folderId     {@link java.util.UUID} of the {@link  com.nedap.archie.rm.directory.Folder} to be fetched from the DB.
     * @return the {@link I_FolderAccess} that provides DB access to the {@link  com.nedap.archie.rm.directory.Folder} that corresponds to the provided folderId param.
     * @throws Exception
     */
    public static I_FolderAccess retrieveInstanceForExistingFolder(I_DomainAccess domainAccess, UUID folderId) {

        /***1-retrieve CTE as a table that contains all the rows that allow to infer each parent-child relationship***/
        FolderHierarchy sf = FOLDER_HIERARCHY.as("sf");

        Table<?> sf_table = table(
                select()
                        .from(FOLDER_HIERARCHY));

        Table<?> folder_table = table(
                select()
                        .from(FOLDER)).as("t_folder1");
        Table<?> folder_table2 = table(
                select()
                        .from(FOLDER)).as("t_folder2");

        Table<?> initial_table = table(
                select()
                        .from(FOLDER_HIERARCHY)
                        .where(
                                FOLDER_HIERARCHY.PARENT_FOLDER.eq(folderId)));

        Field<UUID> subfolderChildFolder = field("subfolders.{0}", FOLDER_HIERARCHY.CHILD_FOLDER.getDataType(), FOLDER_HIERARCHY.CHILD_FOLDER.getUnqualifiedName());
        Field<UUID> subfolderParentFolderRef = field(name("subfolders", "parent_folder"), UUID.class);
        Result<Record> folderSelectedRecordSub = domainAccess.getContext().withRecursive("subfolders").as(
                select(ArrayUtils.addAll(initial_table.fields(), folder_table.fields())).
                        from(initial_table).
                        leftJoin(folder_table).on(initial_table.field("parent_folder", FOLDER_HIERARCHY.PARENT_FOLDER.getType()).eq(
                        folder_table.field("id", FOLDER.ID.getType()))).
                        union(
                                (select(ArrayUtils.addAll(sf_table.fields(), folder_table2.fields())).from(sf_table).
                                        innerJoin("subfolders").on(sf_table.field("parent_folder", FOLDER_HIERARCHY.PARENT_FOLDER.getType()).
                                        eq(subfolderChildFolder))).leftJoin(folder_table2).on(
                                        folder_table2.field("id", FOLDER.ID.getType()).eq(subfolderChildFolder)))
        ).select().from(table(name("subfolders"))).fetch();

        /**2-Reconstruct hierarchical structure from DB result**/
        Map<UUID, Map<UUID, I_FolderAccess>> fHierarchyMap = new TreeMap<UUID, Map<UUID, I_FolderAccess>>();
        for (Record record : folderSelectedRecordSub) {

            //1-create a folder access for the record if needed
            if (!fHierarchyMap.containsKey((UUID) record.getValue("parent_folder"))) {
                fHierarchyMap.put((UUID) record.getValue("parent_folder"), new TreeMap<>());
            }
            fHierarchyMap.get(record.getValue("parent_folder")).put((UUID) record.getValue("child_folder"), buildFolderAccessFromFolderId((UUID) record.getValue("child_folder"), domainAccess, folderSelectedRecordSub));
        }

        /**3-populate result and return**/
        return FolderAccess.buildFolderAccessHierarchy(fHierarchyMap, folderId, null, folderSelectedRecordSub, domainAccess);
    }

    /**
     * Builds the {@link I_FolderAccess} for persisting the {@link  com.nedap.archie.rm.directory.Folder} provided as param.
     *
     * @param domainAccess providing the information about the DB connection.
     * @param folder       to define the {@link I_FolderAccess} that allows its DB access.
     * @param dateTime     that will be set as transaction date when the {@link  com.nedap.archie.rm.directory.Folder} is persisted
     * @param ehrId        of the {@link com.nedap.archie.rm.ehr.Ehr} that references the {@link  com.nedap.archie.rm.directory.Folder} provided as param.
     * @return {@link I_FolderAccess} with the information to persist the provided {@link  com.nedap.archie.rm.directory.Folder}
     */
    public static I_FolderAccess getNewFolderAccessInstance(final I_DomainAccess domainAccess, final Folder folder, final DateTime dateTime, final UUID ehrId) {
        return buildFolderAccessTreeRecursively(domainAccess, folder, null, dateTime, ehrId, null);
    }

    /**
     * Deletes the FOLDER identified with the Folder.id provided and all its subfolders recursively.
     *
     * @param folderId of the {@link  com.nedap.archie.rm.directory.Folder} to delete.
     * @return number of the total {@link  com.nedap.archie.rm.directory.Folder} deleted recursively.
     */
    private Integer delete(final UUID folderId) {

        if (folderId == null) {
            throw new IllegalArgumentException("The folder UID provided for performing a delete operation cannot be null.");
        }

        /**SQL code for the recursive call generated inside the delete that retrieves children iteratively.
         * WITH RECURSIVE subfolders AS (
         * 		SELECT parent_folder, child_folder, in_contribution, sys_transaction
         * 		FROM ehr.folder_hierarchy
         * 		WHERE parent_folder = '00550555-ec91-4025-838d-09ddb4e999cb'
         * 	UNION
         * 		SELECT sf.parent_folder, sf.child_folder, sf.in_contribution, sf.sys_transaction
         * 		FROM ehr.folder_hierarchy sf
         * 		INNER JOIN subfolders s ON sf.parent_folder=s.child_folder
         * ) SELECT * FROM subfolders
         */
        int result;

        Table<?> sf_table = table(
                select()
                        .from(FOLDER_HIERARCHY));

        Table<?> initial_table = table(
                select()
                        .from(FOLDER_HIERARCHY)
                        .where(
                                FOLDER_HIERARCHY.PARENT_FOLDER.eq(folderId)));

        Field<UUID> subfolderChildFolder = field("subfolders.{0}", FOLDER_HIERARCHY.CHILD_FOLDER.getDataType(), FOLDER_HIERARCHY.CHILD_FOLDER.getUnqualifiedName());

        result = this.getContext().delete(FOLDER).where(FOLDER.ID.in(this.getContext().withRecursive("subfolders").as(
                select(initial_table.fields()).
                        from(initial_table).
                        union(
                                (select(sf_table.fields()).from(sf_table).
                                        innerJoin("subfolders").on(sf_table.field("parent_folder", FOLDER_HIERARCHY.PARENT_FOLDER.getType()).
                                        eq(subfolderChildFolder))))
                ).select()
                        .from(table(name("subfolders")))
                        .fetch()
                        .getValues(field(name("child_folder")))
        ))
                .or(FOLDER.ID.eq(folderId))
                .execute();

        return result;
    }


    /**
     * Create a new FolderAccess that contains the full hierarchy of its corresponding {@link I_FolderAccess} children that represents the subfolders.
     *
     * @param fHierarchyMap           {@link java.util.Map} containing as key the UUID of each Folder, and as value an internal Map. For the internal Map the key is the the UUID of a child {@link  com.nedap.archie.rm.directory.Folder}, and the value is the {@link I_FolderAccess} for enabling DB access to this child.
     * @param currentFolder           {@link java.util.UUID} of the current {@link  com.nedap.archie.rm.directory.Folder} to treat in the current recursive call of the method.
     * @param parentFa                the parent {@link I_FolderAccess} that corresponds to the parent  {@link  com.nedap.archie.rm.directory.Folder} of the {@link  com.nedap.archie.rm.directory.Folder} identified as current.
     * @param folderSelectedRecordSub {@link org.jooq.Result} containing the Records that represent the rows to retrieve from the DB corresponding to the children hierarchy.
     * @param domainAccess            containing the information of the DB connection.
     * @return I_FolderAccess populated with its appropriate subfolders as FolderAccess objects.
     * @throws Exception
     */
    private static I_FolderAccess buildFolderAccessHierarchy(final Map<UUID, Map<UUID, I_FolderAccess>> fHierarchyMap, final UUID currentFolder, final I_FolderAccess parentFa, final Result<Record> folderSelectedRecordSub, final I_DomainAccess domainAccess) {
        if ((parentFa != null) && (parentFa.getSubfoldersList().keySet().contains(currentFolder))) {
            return parentFa.getSubfoldersList().get(currentFolder);
        }
        I_FolderAccess folderAccess = buildFolderAccessFromFolderId(currentFolder, domainAccess, folderSelectedRecordSub);
        if (parentFa != null) {
            parentFa.getSubfoldersList().put(currentFolder, folderAccess);
        }
        if (fHierarchyMap.get(currentFolder) != null) {//if not leave node call children

            for (UUID newChild : fHierarchyMap.get(currentFolder).keySet()) {
                buildFolderAccessHierarchy(fHierarchyMap, newChild, folderAccess, folderSelectedRecordSub, domainAccess);
            }
        }
        return folderAccess;
    }

    /**
     * Create a new {@link FolderAccess} from a {@link org.jooq.Record} DB record
     *
     * @param record_      record containing all the information to build one folder-subfolder relationship.
     * @param domainAccess containing the DB connection information.
     * @return FolderAccess instance
     */
    private static FolderAccess buildFolderAccessFromGenericRecord(final Record record_,
                                                                   final I_DomainAccess domainAccess) {

        Record13<UUID, UUID, UUID, Timestamp, Object, UUID, UUID, String, String, Boolean, JSONB, Timestamp, AbstractMap.SimpleEntry<OffsetDateTime, OffsetDateTime>>
                record
                = (Record13<UUID, UUID, UUID, Timestamp, Object, UUID, UUID, String, String, Boolean, JSONB, Timestamp, AbstractMap.SimpleEntry<OffsetDateTime, OffsetDateTime>>) record_;
        FolderAccess folderAccess = new FolderAccess(domainAccess);
        folderAccess.folderRecord = new FolderRecord();
        folderAccess.setFolderId(record.value1());
        folderAccess.setInContribution(record.value7());
        folderAccess.setFolderName(record.value8());
        folderAccess.setFolderNArchetypeNodeId(record.value9());
        folderAccess.setIsFolderActive(record.value10());
        // Due to generic type from JOIN The ItemStructure binding does not cover the details
        // and we have to parse it manually
        folderAccess.setFolderDetails(new OtherDetailsJsonbBinder().converter().from(record.value11()));
        folderAccess.setFolderSysTransaction(record.value12());
        folderAccess.setFolderSysPeriod(new SysPeriodBinder().converter().from(record.value13()));
        folderAccess.getItems()
                .addAll(FolderAccess.retrieveItemsByFolderAndContributionId(record.value1(),
                        record.value7(),
                        domainAccess));

        return folderAccess;
    }

    /**
     * Create a new FolderAccess from a {@link FolderRecord} DB record
     *
     * @param record_      containing the information of a {@link  com.nedap.archie.rm.directory.Folder} in the DB.
     * @param domainAccess containing the DB connection information.
     * @return FolderAccess instance corresponding to the org.ehrbase.jooq.pg.tables.records.FolderRecord provided.
     */
    private static FolderAccess buildFolderAccessFromFolderRecord(final FolderRecord record_,
                                                                  final I_DomainAccess domainAccess) {
        FolderRecord record = record_;
        FolderAccess folderAccess = new FolderAccess(domainAccess);
        folderAccess.folderRecord = new FolderRecord();
        folderAccess.setFolderId(record.getId());
        folderAccess.setInContribution(record.getInContribution());
        folderAccess.setFolderName(record.getName());
        folderAccess.setFolderNArchetypeNodeId(record.getArchetypeNodeId());
        folderAccess.setIsFolderActive(record.getActive());
        folderAccess.setFolderDetails(record.getDetails());
        folderAccess.setFolderSysTransaction(record.getSysTransaction());
        folderAccess.setFolderSysPeriod(record.getSysPeriod());
        folderAccess.getItems()
                .addAll(FolderAccess.retrieveItemsByFolderAndContributionId(record.getId(),
                        record.getInContribution(),
                        domainAccess));
        return folderAccess;
    }

    /**
     * Given a UUID for a folder creates the corresponding FolderAccess from the information conveyed by the {@link org.jooq.Result} provided. Alternatively queries the DB if the information needed is not in {@link org.jooq.Result}.
     * * @param id of the folder to define a {@link FolderAccess} from.
     * * @param {@link org.jooq.Result} containing the Records that represent the rows to retrieve from the DB corresponding to the children hierarchy.
     *
     * @return a FolderAccess corresponding to the Folder id provided
     */
    private static FolderAccess buildFolderAccessFromFolderId(final UUID id, final I_DomainAccess domainAccess, final Result<Record> folderSelectedRecordSub) {

        for (Record record : folderSelectedRecordSub) {
            //if the FOLDER items were returned in the recursive query use them and avoid a DB transaction
            if (record.getValue("parent_folder").equals(id)) {

                return buildFolderAccessFromGenericRecord(record, domainAccess);
            }
        }

        //if no data from the Folder has been already recovered for the id of the folder, then query the DB for it.
        FolderRecord folderSelectedRecord = domainAccess.getContext().selectFrom(FOLDER).where(FOLDER.ID.eq(id)).fetchOne();

        if (folderSelectedRecord == null || folderSelectedRecord.size() < 1) {
            throw new ObjectNotFoundException(
                    "folder", "Folder with id " + id + " could not be found"
            );
        }


        return buildFolderAccessFromFolderRecord(folderSelectedRecord, domainAccess);
    }

    /**
     * Builds the FolderAccess with the collection of subfolders empty.
     *
     * @param domainAccess providing the information about the DB connection.
     * @param folder       to define a corresponding {@link I_FolderAccess} for allowing its persistence.
     * @param timestamp     that will be set as transaction date when the {@link  com.nedap.archie.rm.directory.Folder} is persisted
     * @param ehrId        of the {@link com.nedap.archie.rm.ehr.Ehr} that references this {@link  com.nedap.archie.rm.directory.Folder}
     * @return {@link I_FolderAccess} with the information to persist the provided {@link  com.nedap.archie.rm.directory.Folder}
     */
    public static I_FolderAccess buildPlainFolderAccess(final I_DomainAccess domainAccess, final Folder folder, final Timestamp timestamp, final UUID ehrId, final I_ContributionAccess contributionAccess) {

        FolderAccess folderAccessInstance = new FolderAccess(domainAccess, ehrId, contributionAccess);
        folderAccessInstance.setEhrId(ehrId);
        // In case of creation we have no folderId since it will be created from DB
        if (folder.getUid() != null) {
            UIDBasedId uid = folder.getUid();
            int i = uid.getValue().indexOf("::");
            String uidString;
            if (i < 0) {
                uidString = uid.getValue();
            } else {
                uidString = uid.getValue().substring(0, i);
            }
            folderAccessInstance.setFolderId(UUID.fromString(uidString));
        }
        folderAccessInstance.setInContribution(folderAccessInstance.getContributionAccess().getId());
        folderAccessInstance.setFolderName(folder.getName().getValue());
        folderAccessInstance.setFolderNArchetypeNodeId(folder.getArchetypeNodeId());
        folderAccessInstance.setIsFolderActive(true);

        // TODO: Are these guards required?
        if (folder.getDetails() != null) {
            folderAccessInstance.setFolderDetails(folder.getDetails());
        }

        if (folder.getItems() != null && !folder.getItems().isEmpty()) {
            folderAccessInstance.getItems().addAll(folder.getItems());
        }

        folderAccessInstance.setFolderSysTransaction(new Timestamp(DateTime.now().getMillis()));
        return folderAccessInstance;
    }

    /**
     * Retrieves a list containing the items as ObjectRefs of the folder corresponding to the id provided.
     *
     * @param folderId        of the FOLDER that the items correspond to.
     * @param in_contribution contribution that establishes the reference between a FOLDER and its item.
     * @param domainAccess    connection DB data.
     * @return
     */
    private static List<ObjectRef> retrieveItemsByFolderAndContributionId(UUID folderId, UUID in_contribution, I_DomainAccess domainAccess) {
        Result<Record> retrievedRecords = domainAccess.getContext().with("folderItemsSelect").as(
                select(FOLDER_ITEMS.OBJECT_REF_ID.as("object_ref_id"), FOLDER_ITEMS.IN_CONTRIBUTION.as("item_in_contribution"))
                        .from(FOLDER_ITEMS)
                        .where(FOLDER_ITEMS.FOLDER_ID.eq(folderId)))
                .select()
                .from(OBJECT_REF, table(name("folderItemsSelect")))

                .where(field(name("object_ref_id"), FOLDER_ITEMS.OBJECT_REF_ID.getType()).eq(OBJECT_REF.ID)
                        .and(field(name("item_in_contribution"), FOLDER_ITEMS.IN_CONTRIBUTION.getType()).eq(OBJECT_REF.IN_CONTRIBUTION))).fetch();


        List<ObjectRef> result = new ArrayList<>();
        for (Record recordRecord : retrievedRecords) {
            Record8<String, String, UUID, UUID, Timestamp, AbstractMap.SimpleEntry<OffsetDateTime, OffsetDateTime>, UUID, UUID> recordParam = (Record8<String, String, UUID, UUID, Timestamp, AbstractMap.SimpleEntry<OffsetDateTime, OffsetDateTime>, UUID, UUID>) recordRecord;
            ObjectRefRecord objectRef = new ObjectRefRecord();
            objectRef.setIdNamespace(recordParam.value1());
            objectRef.setType(recordParam.value2());
            objectRef.setId(recordParam.value3());
            objectRef.setInContribution(recordParam.value4());
            objectRef.setSysTransaction(recordParam.value5());
            objectRef.setSysPeriod(new SysPeriodBinder().converter().from(recordParam.value6()));
            objectRef.setId(recordParam.value7());
            result.add(parseObjectRefRecordIntoObjectRef(objectRef, domainAccess));
        }
        return result;
    }

    /**
     * Transforms a ObjectRef DB record into a Reference Model object.
     *
     * @param objectRefRecord
     * @param domainAccess
     * @return the reference model object.
     */
    private static ObjectRef parseObjectRefRecordIntoObjectRef(ObjectRefRecord objectRefRecord, I_DomainAccess domainAccess) {
        ObjectRef result = new ObjectRef();
        ObjectRefId oref = new FolderAccess(domainAccess).new ObjectRefId(objectRefRecord.getId().toString());
        result.setId(new ObjectVersionId(oref.getValue()));
        result.setType(objectRefRecord.getType());
        result.setNamespace(objectRefRecord.getIdNamespace());
        return result;
    }


    /**
     * Recursive method for populating the hierarchy of {@link I_FolderAccess}  for a given {@link  com.nedap.archie.rm.directory.Folder}.
     *
     * @param domainAccess       providing the information about the DB connection.
     * @param current            {@link  com.nedap.archie.rm.directory.Folder} explored in the current iteration.
     * @param parent             folder of the {@link  com.nedap.archie.rm.directory.Folder} procided as the current parameter.
     * @param dateTime           of the transaction that will be stored inthe DB.
     * @param ehrId              of the {@link com.nedap.archie.rm.ehr.Ehr} referencing the current {@link  com.nedap.archie.rm.directory.Folder}.
     * @param contributionAccess that corresponds to the contribution that the {@link  com.nedap.archie.rm.directory.Folder} refers to.
     * @return {@link I_FolderAccess} with the complete hierarchy of sub-folders represented as {@link I_FolderAccess}.
     * @throws Exception
     */
    private static I_FolderAccess buildFolderAccessTreeRecursively(final I_DomainAccess domainAccess, final Folder current, final FolderAccess parent, final DateTime dateTime, final UUID ehrId, final I_ContributionAccess contributionAccess) {
        I_FolderAccess folderAccess = null;

        //if the parent already contains the FolderAccess for the specified folder return the corresponding FolderAccess
        if ((parent != null) && (parent.getSubfoldersList().containsKey(UUID.fromString(current.getUid().getValue())))) {
            return parent.getSubfoldersList().get(current.getUid());
        }
        //create the corresponding FolderAccess for the current folder
        folderAccess = FolderAccess.buildPlainFolderAccess(domainAccess, current, Timestamp.from(Instant.now()), ehrId, contributionAccess);
        //add to parent subfolder list
        if (parent != null) {
            parent.getSubfoldersList().put(((FolderAccess) folderAccess).getFolderRecord().getId(), folderAccess);
        }
        for (Folder child : current.getFolders()) {
            buildFolderAccessTreeRecursively(domainAccess, child, (FolderAccess) folderAccess, dateTime, ehrId, ((FolderAccess) folderAccess).getContributionAccess());
        }
        return folderAccess;
    }

    /**
     * Builds a folderAccess hierarchy recursively by iterating over all sub folders of given folder
     * instance. This works for all folders, i.e. from root for an insert as well for a sub folder
     * hierarchy for update.
     *
     * @param domainAccess       - DB connection context
     * @param folder             - Folder to create access for
     * @param timeStamp          - Current time for transaction audit
     * @param ehrId              - Corresponding EHR
     * @param contributionAccess - Contribution instance for creation of all folders
     * @return FolderAccess instance for folder
     */
    public static I_FolderAccess buildNewFolderAccessHierarchy(final I_DomainAccess domainAccess,
                                                               final Folder folder,
                                                               final Timestamp timeStamp,
                                                               final UUID ehrId,
                                                               final I_ContributionAccess contributionAccess) {
        // Create access for the current folder
        I_FolderAccess folderAccess = buildPlainFolderAccess(domainAccess,
                folder,
                timeStamp,
                ehrId,
                contributionAccess);

        if (folder.getFolders() != null &&
                !folder.getFolders()
                        .isEmpty()) {
            // Iterate over sub folders and create FolderAccess for each sub folder
            folder.getFolders()
                    .forEach(child -> {
                        // Call recursive creation of folderAccess for children without uid
                        I_FolderAccess childFolderAccess = buildNewFolderAccessHierarchy(domainAccess,
                                child,
                                timeStamp,
                                ehrId,
                                contributionAccess);
                        folderAccess.getSubfoldersList()
                                .put(UUID.randomUUID(), childFolderAccess);
                    });
        }
        return folderAccess;
    }

    /**
     * @param parentFolder   identifier.
     * @param childFolder    identifier to define the {@link FolderHierarchyRecord} from.
     * @param inContribution contribution that the {@link  com.nedap.archie.rm.directory.Folder} refers to.
     * @param sysTransaction date of the transaction.
     * @param sysPeriod      period of validity of the entity persisted.
     * @return the {@link FolderHierarchyRecord} for persisting the folder identified by the childFolder param.
     */
    private final FolderHierarchyRecord buildFolderHierarchyRecord(final UUID parentFolder, final UUID childFolder, final UUID inContribution, final Timestamp sysTransaction, final Timestamp sysPeriod) {
        FolderHierarchyRecord fhRecord = getContext().newRecord(FolderHierarchy.FOLDER_HIERARCHY);
        fhRecord.setParentFolder(parentFolder);
        fhRecord.setChildFolder(childFolder);
        fhRecord.setInContribution(inContribution);
        fhRecord.setSysTransaction(sysTransaction);
        //fhRecord.setSysPeriod(sysPeriod); sys period can be left to null so the system sets it for the temporal tables.
        return fhRecord;
    }

    /**
     * Returns the last version number of a given folder by counting all
     * previous versions of a given folder id. If there are no previous versions
     * in the history table the version number will be 1. Otherwise the current
     * version equals the count of entries in the folder history table plus 1.
     *
     * @param domainAccess - Database connection access context
     * @param folderId     - ObjectVersionUid of the folder to check for the last version
     * @return Latest version number for the folder
     */
    public static Integer getLastVersionNumber(I_DomainAccess domainAccess, ObjectVersionId folderId) {

        UUID folderUuid = FolderUtils.extractUuidFromObjectVersionId(folderId);

        if (!hasPreviousVersion(domainAccess, folderUuid)) {
            return 1;
        }
        // Get number of entries as the history table of folders
        int versionCount = domainAccess
                .getContext()
                .fetchCount(FOLDER_HISTORY, FOLDER_HISTORY.ID.eq(folderUuid));
        // Latest version will be entries plus actual entry count (always 1)
        return versionCount + 1;
    }

    /**
     * Checks if there are existing entries for given folder uuid at the folder
     * history table. If there are entries existing, the folder has been
     * modified during previous actions and there are older versions existing.
     *
     * @param domainAccess - Database connection access context
     * @param folderId     - UUID of folder to check
     * @return Folder has previous versions or not
     */
    public static boolean hasPreviousVersion(I_DomainAccess domainAccess, UUID folderId) {
        return domainAccess
                .getContext()
                .fetchExists(FOLDER_HISTORY, FOLDER_HISTORY.ID.eq(folderId));
    }

    /**
     * Evaluates the version for a folder at a given timestamp by counting all rows from folder history with root
     * folder id and a sys_period timestamp before or at given timestamp value as also from the current folder entry
     * if the sys_period provided is also newer than the sys_period of the current folder.
     *
     * @param domainAccess - Database access instance
     * @param rootFolderId - Root folder id
     * @param sysTransaction - Timestamp to get version for
     * @return - Version number that has been current at that point in time
     */
    public static int getVersionNumberAtTime(I_DomainAccess domainAccess, final ObjectVersionId rootFolderId, final Timestamp sysTransaction) {

        UUID folderUuid = FolderUtils.extractUuidFromObjectVersionId(rootFolderId);

        // Check if the timestamp also includes the current folder
        int folderCount = domainAccess
                .getContext()
                .fetchCount(FOLDER, FOLDER.ID.equal(folderUuid).and(FOLDER.SYS_TRANSACTION.lessOrEqual(sysTransaction)));

        // Count all history entries for the root folder
        int folderHistoryCount = domainAccess
                .getContext()
                .fetchCount(FOLDER_HISTORY, FOLDER_HISTORY.ID.equal(folderUuid).and(FOLDER_HISTORY.SYS_TRANSACTION.lessOrEqual(sysTransaction)));

        if (folderHistoryCount <= 0) {
            // No history entries found

            if (folderCount <= 0) {
                // Also no current entries
                throw new ObjectNotFoundException(
                        "directory",
                        "No folder found for " + rootFolderId + " at time " + sysTransaction.toLocalDateTime().toString()
                );
            }

            return folderCount;
        }

        // If we found entries in both tables return the sum. If there is no current entry the count will be 0
        return folderHistoryCount + folderCount;
    }

    public static Timestamp getTimestampForVersion(I_DomainAccess domainAccess, final ObjectVersionId rootFolderId, Integer version) {
        Timestamp timestamp = new Timestamp(new Date().getTime());
        UUID rootFolderUuid = FolderUtils.extractUuidFromObjectVersionId(rootFolderId);
        // Get latest version number
        int currentVersion = FolderAccess.getVersionNumberAtTime(domainAccess, rootFolderId, timestamp);

        if (currentVersion > version) {
            // Select number of rows from folder history record that are required
            Result<FolderHistoryRecord> folderHistoryRecords = domainAccess
                    .getContext()
                    .selectFrom(FOLDER_HISTORY)
                    .where(FOLDER_HISTORY.ID.equal(rootFolderUuid))
                    .orderBy(FOLDER_HISTORY.SYS_TRANSACTION.desc())
                    .limit(currentVersion - version)
                    .fetch();
            // Return sys_transaction timestamp of last entry if existing
            if (folderHistoryRecords.size() > 0) {
                timestamp = folderHistoryRecords.get(folderHistoryRecords.size() - 1).get(FOLDER_HISTORY.SYS_TRANSACTION);
            }
        }
        // The timestamp now contains either the last entry found in folder history or the current time if the desired
        // version matches or is greater than the latest.
        return timestamp;
    }

    /****Getters and Setters for the FolderRecord to store****/
    public UUID getEhrId() {
        return ehrId;
    }

    public void setEhrId(final UUID ehrId) {
        this.ehrId = ehrId;
    }


    public I_ContributionAccess getContributionAccess() {
        return contributionAccess;
    }

    public void setContributionAccess(final I_ContributionAccess contributionAccess) {
        this.contributionAccess = contributionAccess;
    }

    FolderRecord getFolderRecord() {
        return folderRecord;
    }

    public void setSubfoldersList(final Map<UUID, I_FolderAccess> subfolders) {
        this.subfoldersList = subfolders;

    }

    @Override
    public Map<UUID, I_FolderAccess> getSubfoldersList() {
        return this.subfoldersList;
    }

    @Override
    public void setDetails(final ItemStructure details) {
        this.details = details;
    }

    @Override
    public ItemStructure getDetails() {
        return null;
    }

    @Override
    public List<ObjectRef> getItems() {
        return this.items;
    }

    @Override
    public UUID getFolderId() {

        return this.folderRecord.getId();
    }

    @Override
    public void setFolderId(UUID folderId) {

        this.folderRecord.setId(folderId);
    }

    @Override
    public UUID getInContribution() {
        return this.folderRecord.getInContribution();
    }

    @Override
    public void setInContribution(UUID inContribution) {

        this.folderRecord.setInContribution(inContribution);
    }

    @Override
    public String getFolderName() {

        return this.folderRecord.getName();
    }

    @Override
    public void setFolderName(String folderName) {

        this.folderRecord.setName(folderName);
    }

    @Override
    public String getFolderArchetypeNodeId() {

        return this.folderRecord.getArchetypeNodeId();
    }

    @Override
    public void setFolderNArchetypeNodeId(String folderArchetypeNodeId) {

        this.folderRecord.setArchetypeNodeId(folderArchetypeNodeId);
    }

    @Override
    public boolean isFolderActive() {

        return this.folderRecord.getActive();
    }

    @Override
    public void setIsFolderActive(boolean folderActive) {

        this.folderRecord.setActive(folderActive);
    }

    @Override
    public ItemStructure getFolderDetails() {

        return this.folderRecord.getDetails();
    }

    @Override
    public void setFolderDetails(ItemStructure folderDetails) {

        this.folderRecord.setDetails(folderDetails);
    }

    @Override
    public void setFolderSysTransaction(Timestamp folderSysTransaction) {

        this.folderRecord.setSysTransaction(folderSysTransaction);
    }

    @Override
    public Timestamp getFolderSysTransaction() {

        return this.folderRecord.getSysTransaction();
    }

    @Override
    public AbstractMap.SimpleEntry<OffsetDateTime, OffsetDateTime> getFolderSysPeriod() {

        return this.folderRecord.getSysPeriod();
    }

    @Override
    public void setFolderSysPeriod(AbstractMap.SimpleEntry<OffsetDateTime, OffsetDateTime> folderSysPeriod) {

        this.folderRecord.setSysPeriod(folderSysPeriod);
    }

    @Override
    public DataAccess getDataAccess() {
        return this;
    }

    @Override
    public int compareTo(final FolderAccess o) {
        return o.getFolderRecord().getId().compareTo(this.folderRecord.getId());
    }

    private class ObjectRefId extends ObjectId {
        public ObjectRefId(final String value) {
            super(value);
        }
    }

    @Override
    public void adminDeleteFolder() {
        AdminApiUtils adminApi = new AdminApiUtils(getContext());

        adminApi.deleteFolder(this.getFolderId(), true);
    }
}