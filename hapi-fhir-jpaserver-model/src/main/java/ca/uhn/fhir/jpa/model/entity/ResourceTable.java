/*
 * #%L
 * HAPI FHIR JPA Model
 * %%
 * Copyright (C) 2014 - 2025 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.model.entity;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.model.cross.IBasePersistedResource;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.dao.JpaPidIdentifierBridge;
import ca.uhn.fhir.jpa.model.dao.JpaPidValueBridge;
import ca.uhn.fhir.jpa.model.search.ExtendedHSearchIndexData;
import ca.uhn.fhir.jpa.model.search.ResourceTableRoutingBinder;
import ca.uhn.fhir.jpa.model.search.SearchParamTextPropertyBinder;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.Constants;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.hibernate.Session;
import org.hibernate.annotations.GenerationTime;
import org.hibernate.annotations.GeneratorType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.search.engine.backend.types.Projectable;
import org.hibernate.search.engine.backend.types.Searchable;
import org.hibernate.search.mapper.pojo.bridge.mapping.annotation.IdentifierBridgeRef;
import org.hibernate.search.mapper.pojo.bridge.mapping.annotation.PropertyBinderRef;
import org.hibernate.search.mapper.pojo.bridge.mapping.annotation.RoutingBinderRef;
import org.hibernate.search.mapper.pojo.bridge.mapping.annotation.ValueBridgeRef;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.DocumentId;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.IndexingDependency;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.ObjectPath;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.PropertyBinding;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.PropertyValue;
import org.hibernate.tuple.ValueGenerator;
import org.hibernate.type.SqlTypes;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.InstantType;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static ca.uhn.fhir.jpa.model.entity.ResourceTable.IDX_RES_TYPE_FHIR_ID;

@Indexed(routingBinder = @RoutingBinderRef(type = ResourceTableRoutingBinder.class))
@Entity
@Table(
		name = ResourceTable.HFJ_RESOURCE,
		uniqueConstraints = {
			@UniqueConstraint(
					name = IDX_RES_TYPE_FHIR_ID,
					columnNames = {"PARTITION_ID", "RES_TYPE", "FHIR_ID"})
		},
		indexes = {
			// Do not reuse previously used index name: IDX_INDEXSTATUS, IDX_RES_TYPE
			@Index(name = "IDX_RES_DATE", columnList = BaseHasResource.RES_UPDATED),
			@Index(name = "IDX_RES_FHIR_ID", columnList = "FHIR_ID"),
			@Index(
					name = "IDX_RES_TYPE_DEL_UPDATED",
					columnList = "RES_TYPE,RES_DELETED_AT,RES_UPDATED,PARTITION_ID,RES_ID"),
			@Index(name = "IDX_RES_RESID_UPDATED", columnList = "RES_ID, RES_UPDATED, PARTITION_ID")
		})
@NamedEntityGraph(name = "Resource.noJoins")
public class ResourceTable extends BaseHasResource<JpaPid> implements Serializable, IBasePersistedResource<JpaPid> {
	public static final int RESTYPE_LEN = 40;
	public static final String HFJ_RESOURCE = "HFJ_RESOURCE";
	public static final String RES_TYPE = "RES_TYPE";
	public static final String FHIR_ID = "FHIR_ID";
	private static final int MAX_LANGUAGE_LENGTH = 20;
	private static final long serialVersionUID = 1L;
	public static final int MAX_FORCED_ID_LENGTH = 100;
	public static final String IDX_RES_TYPE_FHIR_ID = "IDX_RES_TYPE_FHIR_ID";

	/**
	 * Holds the narrative text only - Used for Fulltext searching but not directly stored in the DB
	 * Note the extra config needed in HS6 for indexing transient props:
	 * https://docs.jboss.org/hibernate/search/6.0/migration/html_single/#indexed-transient-requires-configuration
	 * <p>
	 * Note that we depend on `myVersion` updated for this field to be indexed.
	 */
	@Transient
	@FullTextField(
			name = "myContentText",
			searchable = Searchable.YES,
			projectable = Projectable.YES,
			analyzer = "standardAnalyzer")
	@OptimisticLock(excluded = true)
	@IndexingDependency(derivedFrom = @ObjectPath(@PropertyValue(propertyName = "myVersion")))
	private String myContentText;

	@Column(name = "HASH_SHA256", length = 64, nullable = true)
	@OptimisticLock(excluded = true)
	private String myHashSha256;

	@Column(name = "SP_HAS_LINKS", nullable = false)
	@OptimisticLock(excluded = true)
	private boolean myHasLinks;

	@EmbeddedId
	@DocumentId(identifierBridge = @IdentifierBridgeRef(type = JpaPidIdentifierBridge.class))
	@GenericField(
			name = "myId",
			projectable = Projectable.YES,
			valueBridge = @ValueBridgeRef(type = JpaPidValueBridge.class))
	private JpaPid myPid;

	@Column(name = PartitionablePartitionId.PARTITION_ID, nullable = true, insertable = false, updatable = false)
	private Integer myPartitionIdValue;

	@Column(name = PartitionablePartitionId.PARTITION_DATE, nullable = true)
	private LocalDate myPartitionDateValue;

	@Column(name = "SP_INDEX_STATUS", nullable = true)
	@Enumerated(EnumType.ORDINAL)
	@JdbcTypeCode(SqlTypes.TINYINT)
	@OptimisticLock(excluded = true)
	private EntityIndexStatusEnum myIndexStatus;

	// TODO: Removed in 5.5.0. Drop in a future release.
	@Column(name = "RES_LANGUAGE", length = MAX_LANGUAGE_LENGTH, nullable = true)
	@OptimisticLock(excluded = true)
	private String myLanguage;

	/**
	 * Holds the narrative text only - Used for Fulltext searching but not directly stored in the DB
	 */
	@Transient()
	@FullTextField(
			name = "myNarrativeText",
			searchable = Searchable.YES,
			projectable = Projectable.YES,
			analyzer = "standardAnalyzer")
	@OptimisticLock(excluded = true)
	@IndexingDependency(derivedFrom = @ObjectPath(@PropertyValue(propertyName = "myVersion")))
	private String myNarrativeText;

	@Transient
	@IndexingDependency(derivedFrom = @ObjectPath(@PropertyValue(propertyName = "myVersion")))
	@PropertyBinding(binder = @PropertyBinderRef(type = SearchParamTextPropertyBinder.class))
	private ExtendedHSearchIndexData myLuceneIndexData;

	@OneToMany(
			mappedBy = "myResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	@OptimisticLock(excluded = true)
	private Collection<ResourceIndexedSearchParamCoords> myParamsCoords;

	@Column(name = "SP_COORDS_PRESENT", nullable = false)
	@OptimisticLock(excluded = true)
	private boolean myParamsCoordsPopulated;

	@OneToMany(
			mappedBy = "myResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	@OptimisticLock(excluded = true)
	private Collection<ResourceIndexedSearchParamDate> myParamsDate;

	@Column(name = "SP_DATE_PRESENT", nullable = false)
	@OptimisticLock(excluded = true)
	private boolean myParamsDatePopulated;

	@OptimisticLock(excluded = true)
	@OneToMany(
			mappedBy = "myResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	private Collection<ResourceIndexedSearchParamNumber> myParamsNumber;

	@Column(name = "SP_NUMBER_PRESENT", nullable = false)
	@OptimisticLock(excluded = true)
	private boolean myParamsNumberPopulated;

	@OneToMany(
			mappedBy = "myResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	@OptimisticLock(excluded = true)
	private Collection<ResourceIndexedSearchParamQuantity> myParamsQuantity;

	@Column(name = "SP_QUANTITY_PRESENT", nullable = false)
	@OptimisticLock(excluded = true)
	private boolean myParamsQuantityPopulated;

	/**
	 * Added to support UCUM conversion
	 * since 5.3.0
	 */
	@OneToMany(
			mappedBy = "myResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	@OptimisticLock(excluded = true)
	private Collection<ResourceIndexedSearchParamQuantityNormalized> myParamsQuantityNormalized;

	/**
	 * Added to support UCUM conversion,
	 * NOTE : use Boolean class instead of boolean primitive, in order to set the existing rows to null
	 * since 5.3.0
	 */
	@Column(name = "SP_QUANTITY_NRML_PRESENT", nullable = false)
	@OptimisticLock(excluded = true)
	private Boolean myParamsQuantityNormalizedPopulated = Boolean.FALSE;

	@OneToMany(
			mappedBy = "myResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	@OptimisticLock(excluded = true)
	private Collection<ResourceIndexedSearchParamString> myParamsString;

	@Column(name = "SP_STRING_PRESENT", nullable = false)
	@OptimisticLock(excluded = true)
	private boolean myParamsStringPopulated;

	@OneToMany(
			mappedBy = "myResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	@OptimisticLock(excluded = true)
	private Collection<ResourceIndexedSearchParamToken> myParamsToken;

	@Column(name = "SP_TOKEN_PRESENT", nullable = false)
	@OptimisticLock(excluded = true)
	private boolean myParamsTokenPopulated;

	@OneToMany(
			mappedBy = "myResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	@OptimisticLock(excluded = true)
	private Collection<ResourceIndexedSearchParamUri> myParamsUri;

	@Column(name = "SP_URI_PRESENT", nullable = false)
	@OptimisticLock(excluded = true)
	private boolean myParamsUriPopulated;

	// Added in 3.0.0 - Should make this a primitive Boolean at some point
	@OptimisticLock(excluded = true)
	@Column(name = "SP_CMPSTR_UNIQ_PRESENT")
	private Boolean myParamsComboStringUniquePresent = false;

	@OneToMany(
			mappedBy = "myResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	@OptimisticLock(excluded = true)
	private Collection<ResourceIndexedComboStringUnique> myParamsComboStringUnique;

	// Added in 5.5.0 - Should make this a primitive Boolean at some point
	@OptimisticLock(excluded = true)
	@Column(name = "SP_CMPTOKS_PRESENT")
	private Boolean myParamsComboTokensNonUniquePresent = false;

	@OneToMany(
			mappedBy = "myResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	@OptimisticLock(excluded = true)
	private Collection<ResourceIndexedComboTokenNonUnique> myParamsComboTokensNonUnique;

	@OneToMany(
			mappedBy = "mySourceResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	@OptimisticLock(excluded = true)
	private Collection<ResourceLink> myResourceLinks;

	/**
	 * This is a clone of {@link #myResourceLinks} but without the hibernate annotations.
	 * Before we persist we copy the contents of {@link #myResourceLinks} into this field. We
	 * have this separate because that way we can only populate this field if
	 * {@link #myHasLinks} is true, meaning that there are actually resource links present
	 * right now. This avoids Hibernate Search triggering a select on the resource link
	 * table.
	 * <p>
	 * This field is used by FulltextSearchSvcImpl
	 * <p>
	 * You can test that any changes don't cause extra queries by running
	 * FhirResourceDaoR4QueryCountTest
	 */
	@FullTextField
	@Transient
	@IndexingDependency(derivedFrom = @ObjectPath(@PropertyValue(propertyName = "myResourceLinks")))
	private String myResourceLinksField;

	@OneToMany(
			mappedBy = "myTargetResource",
			cascade = {},
			fetch = FetchType.LAZY,
			orphanRemoval = false)
	@OptimisticLock(excluded = true)
	private Collection<ResourceLink> myResourceLinksAsTarget;

	@Column(name = RES_TYPE, length = RESTYPE_LEN, nullable = false)
	@FullTextField
	@OptimisticLock(excluded = true)
	private String myResourceType;

	@OneToMany(mappedBy = "myResource", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
	@OptimisticLock(excluded = true)
	private Collection<SearchParamPresentEntity> mySearchParamPresents;

	@OneToMany(mappedBy = "myResource", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
	@OptimisticLock(excluded = true)
	private Set<ResourceTag> myTags;

	@Transient
	private transient boolean myUnchangedInCurrentOperation;

	/**
	 * The id of the Resource.
	 * Will contain either the client-assigned id, or the sequence value.
	 * Will be null during insert time until the first read.
	 */
	@Column(
			name = FHIR_ID,
			// [A-Za-z0-9\-\.]{1,64} - https://www.hl7.org/fhir/datatypes.html#id
			length = 64,
			// we never update this after insert, and the Generator will otherwise "dirty" the object.
			updatable = false)

	// inject the pk for server-assigned sequence ids.
	@GeneratorType(when = GenerationTime.INSERT, type = FhirIdGenerator.class)
	// Make sure the generator doesn't bump the history version.
	@OptimisticLock(excluded = true)
	private String myFhirId;

	/**
	 * Is there a corresponding row in {@link ResourceSearchUrlEntity} for
	 * this row.
	 * TODO: Added in 6.6.0 - Should make this a primitive boolean at some point
	 */
	@OptimisticLock(excluded = true)
	@Column(name = "SEARCH_URL_PRESENT", nullable = true)
	private Boolean mySearchUrlPresent = false;

	@Version
	@Column(name = "RES_VER", nullable = false)
	private long myVersion;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(
			name = "RES_TYPE_ID",
			referencedColumnName = "RES_TYPE_ID",
			foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT),
			insertable = false,
			updatable = false,
			nullable = true)
	private ResourceTypeEntity myResourceTypeEntity;

	@Column(name = "RES_TYPE_ID", nullable = true)
	private Short myResourceTypeId;

	@OneToMany(mappedBy = "myResourceTable", fetch = FetchType.LAZY)
	private Collection<ResourceHistoryProvenanceEntity> myProvenance;

	@Transient
	private transient ResourceHistoryTable myCurrentVersionEntity;

	@Transient
	private transient boolean myVersionUpdatedInCurrentTransaction;

	@Transient
	private volatile String myCreatedByMatchUrl;

	@Transient
	private volatile String myUpdatedByMatchUrl;

	/**
	 * Constructor
	 */
	public ResourceTable() {
		super();
	}

	/**
	 * Setting this flag is an indication that we're making changes and the version number will
	 * be incremented in the current transaction. When this is set, calls to {@link #getVersion()}
	 * will be incremented by one.
	 * This flag is cleared in {@link #postPersist()} since at that time the new version number
	 * should be reflected.
	 */
	public void markVersionUpdatedInCurrentTransaction() {
		if (!myVersionUpdatedInCurrentTransaction) {
			/*
			 * Note that modifying this number doesn't actually directly affect what
			 * gets stored in the database since this is a @Version field and the
			 * value is therefore managed by Hibernate. So in other words, if the
			 * row in the database is updated, it doesn't matter what we set
			 * this field to, hibernate will increment it by one. However, we still
			 * increment it for two reasons:
			 * 1. The value gets used for the version attribute in the ResourceHistoryTable
			 *    entity we create for each new version.
			 * 2. For updates to existing resources, there may actually not be any other
			 *    changes to this entity so incrementing this is a signal to
			 *    Hibernate that something changed and we need to force an entity
			 *    update.
			 */
			myVersion++;
			this.myVersionUpdatedInCurrentTransaction = true;
		}
	}

	@PostPersist
	public void postPersist() {
		myVersionUpdatedInCurrentTransaction = false;
	}

	@Override
	public ResourceTag addTag(TagDefinition theTag) {
		for (ResourceTag next : getTags()) {
			if (next.getTag().equals(theTag)) {
				return next;
			}
		}
		ResourceTag tag = new ResourceTag(this, theTag, getPartitionId());
		getTags().add(tag);
		return tag;
	}

	public String getHashSha256() {
		return myHashSha256;
	}

	public void setHashSha256(String theHashSha256) {
		myHashSha256 = theHashSha256;
	}

	@Nonnull
	@Override
	public JpaPid getId() {
		if (myPid == null) {
			myPid = new JpaPid();
		}
		myPid.setVersion(myVersion);
		myPid.setPartitionIdIfNotAlreadySet(myPartitionIdValue);
		myPid.setResourceType(myResourceType);
		return myPid;
	}

	public void setId(JpaPid thePid) {
		myPid = thePid;
	}

	@VisibleForTesting
	public void setIdForUnitTest(Long theId) {
		setId(JpaPid.fromId(theId));
	}

	public EntityIndexStatusEnum getIndexStatus() {
		return myIndexStatus;
	}

	public void setIndexStatus(EntityIndexStatusEnum theIndexStatus) {
		myIndexStatus = theIndexStatus;
	}

	public Collection<ResourceIndexedComboStringUnique> getParamsComboStringUnique() {
		if (myParamsComboStringUnique == null) {
			myParamsComboStringUnique = new ArrayList<>();
		}
		return myParamsComboStringUnique;
	}

	public Collection<ResourceIndexedComboTokenNonUnique> getmyParamsComboTokensNonUnique() {
		if (myParamsComboTokensNonUnique == null) {
			myParamsComboTokensNonUnique = new ArrayList<>();
		}
		return myParamsComboTokensNonUnique;
	}

	public Collection<ResourceIndexedSearchParamCoords> getParamsCoords() {
		if (myParamsCoords == null) {
			myParamsCoords = new ArrayList<>();
		}
		return myParamsCoords;
	}

	public void setParamsCoords(Collection<ResourceIndexedSearchParamCoords> theParamsCoords) {
		if (!isParamsTokenPopulated() && theParamsCoords.isEmpty()) {
			return;
		}
		getParamsCoords().clear();
		getParamsCoords().addAll(theParamsCoords);
	}

	public Collection<ResourceIndexedSearchParamDate> getParamsDate() {
		if (myParamsDate == null) {
			myParamsDate = new ArrayList<>();
		}
		return myParamsDate;
	}

	public void setParamsDate(Collection<ResourceIndexedSearchParamDate> theParamsDate) {
		if (!isParamsDatePopulated() && theParamsDate.isEmpty()) {
			return;
		}
		getParamsDate().clear();
		getParamsDate().addAll(theParamsDate);
	}

	public Collection<ResourceIndexedSearchParamNumber> getParamsNumber() {
		if (myParamsNumber == null) {
			myParamsNumber = new ArrayList<>();
		}
		return myParamsNumber;
	}

	public void setParamsNumber(Collection<ResourceIndexedSearchParamNumber> theNumberParams) {
		if (!isParamsNumberPopulated() && theNumberParams.isEmpty()) {
			return;
		}
		getParamsNumber().clear();
		getParamsNumber().addAll(theNumberParams);
	}

	public Collection<ResourceIndexedSearchParamQuantity> getParamsQuantity() {
		if (myParamsQuantity == null) {
			myParamsQuantity = new ArrayList<>();
		}
		return myParamsQuantity;
	}

	public void setParamsQuantity(Collection<ResourceIndexedSearchParamQuantity> theQuantityParams) {
		if (!isParamsQuantityPopulated() && theQuantityParams.isEmpty()) {
			return;
		}
		getParamsQuantity().clear();
		getParamsQuantity().addAll(theQuantityParams);
	}

	public Collection<ResourceIndexedSearchParamQuantityNormalized> getParamsQuantityNormalized() {
		if (myParamsQuantityNormalized == null) {
			myParamsQuantityNormalized = new ArrayList<>();
		}
		return myParamsQuantityNormalized;
	}

	public void setParamsQuantityNormalized(
			Collection<ResourceIndexedSearchParamQuantityNormalized> theQuantityNormalizedParams) {
		if (!isParamsQuantityNormalizedPopulated() && theQuantityNormalizedParams.isEmpty()) {
			return;
		}
		getParamsQuantityNormalized().clear();
		getParamsQuantityNormalized().addAll(theQuantityNormalizedParams);
	}

	public Collection<ResourceIndexedSearchParamString> getParamsString() {
		if (myParamsString == null) {
			myParamsString = new ArrayList<>();
		}
		return myParamsString;
	}

	public void setParamsString(Collection<ResourceIndexedSearchParamString> theParamsString) {
		if (!isParamsStringPopulated() && theParamsString.isEmpty()) {
			return;
		}
		getParamsString().clear();
		getParamsString().addAll(theParamsString);
	}

	public Collection<ResourceIndexedSearchParamToken> getParamsToken() {
		if (myParamsToken == null) {
			myParamsToken = new ArrayList<>();
		}
		return myParamsToken;
	}

	public void setParamsToken(Collection<ResourceIndexedSearchParamToken> theParamsToken) {
		if (!isParamsTokenPopulated() && theParamsToken.isEmpty()) {
			return;
		}
		getParamsToken().clear();
		getParamsToken().addAll(theParamsToken);
	}

	public Collection<ResourceIndexedSearchParamUri> getParamsUri() {
		if (myParamsUri == null) {
			myParamsUri = new ArrayList<>();
		}
		return myParamsUri;
	}

	public void setParamsUri(Collection<ResourceIndexedSearchParamUri> theParamsUri) {
		if (!isParamsTokenPopulated() && theParamsUri.isEmpty()) {
			return;
		}
		getParamsUri().clear();
		getParamsUri().addAll(theParamsUri);
	}

	@Override
	public JpaPid getResourceId() {
		return getId();
	}

	public Collection<ResourceLink> getResourceLinks() {
		if (myResourceLinks == null) {
			myResourceLinks = new ArrayList<>();
		}
		return myResourceLinks;
	}

	public void setResourceLinks(Collection<ResourceLink> theLinks) {
		if (!isHasLinks() && theLinks.isEmpty()) {
			return;
		}
		getResourceLinks().clear();
		getResourceLinks().addAll(theLinks);
	}

	@Override
	public String getResourceType() {
		return myResourceType;
	}

	public ResourceTable setResourceType(String theResourceType) {
		myResourceType = theResourceType;
		return this;
	}

	@Override
	public Short getResourceTypeId() {
		return myResourceTypeId;
	}

	public void setResourceTypeId(Short theResourceTypeId) {
		myResourceTypeId = theResourceTypeId;
	}

	public ResourceTypeEntity getMyResourceTypeEntity() {
		return myResourceTypeEntity;
	}

	@Override
	public Collection<ResourceTag> getTags() {
		if (myTags == null) {
			myTags = new HashSet<>();
		}
		return myTags;
	}

	@Override
	public long getVersion() {
		return myVersion;
	}

	@Nonnull
	@Override
	public PartitionablePartitionId getPartitionId() {
		PartitionablePartitionId retVal = getId().getPartitionablePartitionId();
		if (myPartitionIdValue != null) {
			retVal.setPartitionId(myPartitionIdValue);
		}
		if (myPartitionDateValue != null) {
			retVal.setPartitionDate(myPartitionDateValue);
		}
		return retVal;
	}

	/**
	 * Sets the version on this entity to {@literal 1}. This should only be called
	 * on resources that are not yet persisted. After that time the version number
	 * is managed by hibernate.
	 */
	public void initializeVersion() {
		assert myPid == null || myPid.getId() == null;
		myVersion = 1;
	}

	/**
	 * Don't call this in any JPA environments, the version will be ignored
	 * since this field is managed by hibernate
	 */
	@VisibleForTesting
	public void setVersionForUnitTest(long theVersion) {
		myVersion = theVersion;
	}

	@Override
	public boolean isDeleted() {
		return getDeleted() != null;
	}

	@Override
	public void setNotDeleted() {
		setDeleted(null);
	}

	public boolean isHasLinks() {
		return myHasLinks;
	}

	public void setHasLinks(boolean theHasLinks) {
		myHasLinks = theHasLinks;
	}

	/**
	 * Clears all the index population flags, e.g. {@link #isParamsStringPopulated()}
	 *
	 * @since 6.8.0
	 */
	public void clearAllParamsPopulated() {
		myParamsTokenPopulated = false;
		myParamsCoordsPopulated = false;
		myParamsDatePopulated = false;
		myParamsNumberPopulated = false;
		myParamsStringPopulated = false;
		myParamsQuantityPopulated = false;
		myParamsQuantityNormalizedPopulated = false;
		myParamsUriPopulated = false;
		myHasLinks = false;
	}

	public boolean isParamsComboStringUniquePresent() {
		if (myParamsComboStringUniquePresent == null) {
			return false;
		}
		return myParamsComboStringUniquePresent;
	}

	public void setParamsComboStringUniquePresent(boolean theParamsComboStringUniquePresent) {
		myParamsComboStringUniquePresent = theParamsComboStringUniquePresent;
	}

	public boolean isParamsComboTokensNonUniquePresent() {
		if (myParamsComboTokensNonUniquePresent == null) {
			return false;
		}
		return myParamsComboTokensNonUniquePresent;
	}

	public void setParamsComboTokensNonUniquePresent(boolean theParamsComboTokensNonUniquePresent) {
		myParamsComboTokensNonUniquePresent = theParamsComboTokensNonUniquePresent;
	}

	public boolean isParamsCoordsPopulated() {
		return myParamsCoordsPopulated;
	}

	public void setParamsCoordsPopulated(boolean theParamsCoordsPopulated) {
		myParamsCoordsPopulated = theParamsCoordsPopulated;
	}

	public boolean isParamsDatePopulated() {
		return myParamsDatePopulated;
	}

	public void setParamsDatePopulated(boolean theParamsDatePopulated) {
		myParamsDatePopulated = theParamsDatePopulated;
	}

	public boolean isParamsNumberPopulated() {
		return myParamsNumberPopulated;
	}

	public void setParamsNumberPopulated(boolean theParamsNumberPopulated) {
		myParamsNumberPopulated = theParamsNumberPopulated;
	}

	public boolean isParamsQuantityPopulated() {
		return myParamsQuantityPopulated;
	}

	public void setParamsQuantityPopulated(boolean theParamsQuantityPopulated) {
		myParamsQuantityPopulated = theParamsQuantityPopulated;
	}

	public Boolean isParamsQuantityNormalizedPopulated() {
		if (myParamsQuantityNormalizedPopulated == null) return Boolean.FALSE;
		else return myParamsQuantityNormalizedPopulated;
	}

	public void setParamsQuantityNormalizedPopulated(Boolean theParamsQuantityNormalizedPopulated) {
		if (theParamsQuantityNormalizedPopulated == null) myParamsQuantityNormalizedPopulated = Boolean.FALSE;
		else myParamsQuantityNormalizedPopulated = theParamsQuantityNormalizedPopulated;
	}

	public boolean isParamsStringPopulated() {
		return myParamsStringPopulated;
	}

	public void setParamsStringPopulated(boolean theParamsStringPopulated) {
		myParamsStringPopulated = theParamsStringPopulated;
	}

	public boolean isParamsTokenPopulated() {
		return myParamsTokenPopulated;
	}

	public void setParamsTokenPopulated(boolean theParamsTokenPopulated) {
		myParamsTokenPopulated = theParamsTokenPopulated;
	}

	public boolean isParamsUriPopulated() {
		return myParamsUriPopulated;
	}

	public void setParamsUriPopulated(boolean theParamsUriPopulated) {
		myParamsUriPopulated = theParamsUriPopulated;
	}

	/**
	 * Transient (not saved in DB) flag indicating that this resource was found to be unchanged by the current operation
	 * and was not re-saved in the database
	 */
	public boolean isUnchangedInCurrentOperation() {
		return myUnchangedInCurrentOperation;
	}

	/**
	 * Transient (not saved in DB) flag indicating that this resource was found to be unchanged by the current operation
	 * and was not re-saved in the database
	 */
	public void setUnchangedInCurrentOperation(boolean theUnchangedInCurrentOperation) {

		myUnchangedInCurrentOperation = theUnchangedInCurrentOperation;
	}

	public String getContentText() {
		return myContentText;
	}

	public void setContentText(String theContentText) {
		myContentText = theContentText;
	}

	public void setNarrativeText(String theNarrativeText) {
		myNarrativeText = theNarrativeText;
	}

	public boolean isSearchUrlPresent() {
		return Boolean.TRUE.equals(mySearchUrlPresent);
	}

	public void setSearchUrlPresent(boolean theSearchUrlPresent) {
		mySearchUrlPresent = theSearchUrlPresent;
	}

	/**
	 * This method creates a new history entity, or might reuse the current one if we've
	 * already created one in the current transaction. This is because we can only increment
	 * the version once in a DB transaction (since hibernate manages that number) so creating
	 * multiple {@link ResourceHistoryTable} entities will result in a constraint error.
	 */
	public ResourceHistoryTable toHistory(boolean theCreateVersionTags) {

		ResourceHistoryTable retVal = new ResourceHistoryTable();

		retVal.setResourceId(myPid.getId());
		retVal.setResourceType(myResourceType);
		retVal.setResourceTypeId(myResourceTypeId);
		retVal.setTransientForcedId(getFhirId());
		retVal.setFhirVersion(getFhirVersion());
		retVal.setResourceTable(this);
		retVal.setPartitionId(getPartitionId());

		retVal.setHasTags(isHasTags());
		if (isHasTags() && theCreateVersionTags) {
			for (ResourceTag next : getTags()) {
				retVal.addTag(next);
			}
		}

		// If we've deleted and updated the same resource in the same transaction,
		// we need to actually create 2 distinct versions
		if (getCurrentVersionEntity() != null
				&& getCurrentVersionEntity().getId() != null
				&& getVersion() == getCurrentVersionEntity().getVersion()) {
			myVersion++;
		}

		populateHistoryEntityVersionAndDates(retVal);

		return retVal;
	}

	/**
	 * Updates several temporal values in a {@link ResourceHistoryTable} entity which
	 * are pulled from this entity, including the resource version, and the
	 * creation, update, and deletion dates.
	 */
	public void populateHistoryEntityVersionAndDates(ResourceHistoryTable theResourceHistoryTable) {
		theResourceHistoryTable.setVersion(getVersion());
		theResourceHistoryTable.setPublished(getPublishedDate());
		theResourceHistoryTable.setUpdated(getUpdatedDate());
		theResourceHistoryTable.setDeleted(getDeleted());
	}

	@Override
	public String toString() {
		ToStringBuilder b = new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE);
		b.append("partition", getPartitionId().getPartitionId());
		b.append("pid", getId().getId());
		b.append("fhirId", myFhirId);
		b.append("resourceType", myResourceType);
		b.append("resourceTypeId", getResourceTypeId());
		b.append("version", myVersion);
		b.append("lastUpdated", getUpdated().getValueAsString());
		if (getDeleted() != null) {
			b.append("deleted", new InstantType(getDeleted()).getValueAsString());
		}
		return b.build();
	}

	@PrePersist
	@PreUpdate
	public void preSave() {
		if (myHasLinks && myResourceLinks != null) {
			myResourceLinksField = getResourceLinks().stream()
					.map(ResourceLink::getTargetResourcePid)
					.filter(Objects::nonNull)
					.map(Object::toString)
					.collect(Collectors.joining(" "));
		} else {
			myResourceLinksField = null;
		}
	}

	/**
	 * This is a convenience to avoid loading the version a second time within a single transaction. It is
	 * not persisted.
	 */
	public ResourceHistoryTable getCurrentVersionEntity() {
		return myCurrentVersionEntity;
	}

	/**
	 * This is a convenience to avoid loading the version a second time within a single transaction. It is
	 * not persisted.
	 */
	public void setCurrentVersionEntity(ResourceHistoryTable theCurrentVersionEntity) {
		myCurrentVersionEntity = theCurrentVersionEntity;
	}

	@Override
	public JpaPid getPersistentId() {
		return getId();
	}

	/**
	 * Returns a newly created IdDt containing the resource ID, or <code>null</code> if
	 * the ID is not yet known (e.g. if this entity will have a server-assigned ID which
	 * has not yet been assigned).
	 */
	@Override
	public IdDt getIdDt() {
		return createAndPopulateIdOrReturnNull(IdDt::new);
	}

	/**
	 * Returns a newly created IdType containing the resource ID, or <code>null</code> if
	 * the ID is not yet known (e.g. if this entity will have a server-assigned ID which
	 * has not yet been assigned).
	 */
	public IIdType getIdType(FhirContext theContext) {
		return createAndPopulateIdOrReturnNull(() -> theContext.getVersion().newIdType());
	}

	/**
	 * @param theNewIdInstanceSupplier Creates a new instance of the appropriate ID type
	 */
	@Nullable
	private <T extends IIdType> T createAndPopulateIdOrReturnNull(Supplier<T> theNewIdInstanceSupplier) {
		String resourceId;
		if (myFhirId != null && !myFhirId.isEmpty()) {
			resourceId = myFhirId;
		} else {
			Long id = getResourceId().getId();
			if (id == null) {
				return null;
			}
			resourceId = Long.toString(id);
		}
		T retVal = theNewIdInstanceSupplier.get();
		retVal.setValue(getResourceType() + '/' + resourceId + '/' + Constants.PARAM_HISTORY + '/' + getVersion());
		return retVal;
	}

	public String getCreatedByMatchUrl() {
		return myCreatedByMatchUrl;
	}

	public void setCreatedByMatchUrl(String theCreatedByMatchUrl) {
		myCreatedByMatchUrl = theCreatedByMatchUrl;
	}

	public String getUpdatedByMatchUrl() {
		return myUpdatedByMatchUrl;
	}

	public void setUpdatedByMatchUrl(String theUpdatedByMatchUrl) {
		myUpdatedByMatchUrl = theUpdatedByMatchUrl;
	}

	public boolean isVersionUpdatedInCurrentTransaction() {
		return myVersionUpdatedInCurrentTransaction;
	}

	public void setLuceneIndexData(ExtendedHSearchIndexData theLuceneIndexData) {
		myLuceneIndexData = theLuceneIndexData;
	}

	public Collection<SearchParamPresentEntity> getSearchParamPresents() {
		if (mySearchParamPresents == null) {
			mySearchParamPresents = new ArrayList<>();
		}
		return mySearchParamPresents;
	}

	/**
	 * Get the FHIR resource id.
	 *
	 * @return the resource id, or null if the resource doesn't have a client-assigned id,
	 * and hasn't been saved to the db to get a server-assigned id yet.
	 */
	@Override
	public String getFhirId() {
		return myFhirId;
	}

	public void setFhirId(String theFhirId) {
		myFhirId = theFhirId;
	}

	public String asTypedFhirResourceId() {
		return getResourceType() + "/" + getFhirId();
	}

	public void setPartitionId(PartitionablePartitionId theStoragePartition) {
		if (myPid == null) {
			myPid = new JpaPid();
		}
		myPid.setPartitionId(theStoragePartition.getPartitionId());
		myPartitionIdValue = theStoragePartition.getPartitionId();
		myPartitionDateValue = theStoragePartition.getPartitionDate();
	}

	public void setParamsComboStringUnique(Collection<ResourceIndexedComboStringUnique> theComboStringUniques) {
		myParamsComboStringUnique = theComboStringUniques;
	}

	public void setParamsComboTokensNonUnique(Collection<ResourceIndexedComboTokenNonUnique> theComboTokensNonUnique) {
		myParamsComboTokensNonUnique = theComboTokensNonUnique;
	}

	/**
	 * Populate myFhirId with server-assigned sequence id when no client-id provided.
	 * We eat this complexity during insert to simplify query time with a uniform column.
	 * Server-assigned sequence ids aren't available until just before insertion.
	 * Hibernate calls insert Generators after the pk has been assigned, so we can use myId safely here.
	 */
	public static final class FhirIdGenerator implements ValueGenerator<String> {
		@Override
		public String generateValue(Session session, Object owner) {
			ResourceTable that = (ResourceTable) owner;
			return that.myFhirId != null ? that.myFhirId : that.myPid.getId().toString();
		}
	}
}
