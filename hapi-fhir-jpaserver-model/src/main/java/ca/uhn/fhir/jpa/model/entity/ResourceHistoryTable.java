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

import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.dao.JpaPidFk;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.Constants;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.hibernate.Length;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;

@Entity
@Table(
		name = ResourceHistoryTable.HFJ_RES_VER,
		uniqueConstraints = {
			@UniqueConstraint(
					name = ResourceHistoryTable.IDX_RESVER_ID_VER,
					columnNames = {"PARTITION_ID", "RES_ID", "RES_VER"})
		},
		indexes = {
			@Index(name = "IDX_RESVER_TYPE_DATE", columnList = "RES_TYPE,RES_UPDATED,RES_ID"),
			@Index(name = "IDX_RESVER_ID_DATE", columnList = "RES_ID,RES_UPDATED"),
			@Index(name = "IDX_RESVER_DATE", columnList = "RES_UPDATED,RES_ID"),
			@Index(name = "IDX_RESVER_ID_SRC_URI", columnList = "SOURCE_URI,RES_ID,PARTITION_ID")
		})
public class ResourceHistoryTable extends BaseHasResource<ResourceHistoryTablePk> implements Serializable {
	public static final String IDX_RESVER_ID_VER = "IDX_RESVER_ID_VER";
	public static final int SOURCE_URI_LENGTH = ResourceIndexedSearchParamString.MAX_LENGTH;
	/**
	 * @see ResourceEncodingEnum
	 */
	// Don't reduce the visibility here, we reference this from Smile
	@SuppressWarnings("WeakerAccess")
	public static final int ENCODING_COL_LENGTH = 5;

	public static final String HFJ_RES_VER = "HFJ_RES_VER";
	private static final long serialVersionUID = 1L;

	@EmbeddedId
	private ResourceHistoryTablePk myId;

	@Column(name = PartitionablePartitionId.PARTITION_ID, nullable = true, insertable = false, updatable = false)
	private Integer myPartitionIdValue;

	@SuppressWarnings("unused")
	@Column(name = PartitionablePartitionId.PARTITION_DATE, updatable = false, nullable = true)
	private LocalDate myPartitionDateValue;

	@Override
	@Nullable
	public PartitionablePartitionId getPartitionId() {
		return PartitionablePartitionId.with(getResourceId().getPartitionId(), myPartitionDateValue);
	}

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumns(
			value = {
				@JoinColumn(name = "RES_ID", nullable = false, insertable = false, updatable = false),
				@JoinColumn(name = "PARTITION_ID", nullable = false, insertable = false, updatable = false),
			},
			foreignKey = @ForeignKey(name = "FK_RESOURCE_HISTORY_RESOURCE"))
	private ResourceTable myResourceTable;

	@Embedded
	@AttributeOverride(name = "myId", column = @Column(name = "RES_ID", insertable = true, updatable = false))
	@AttributeOverride(
			name = "myPartitionIdValue",
			column = @Column(name = "PARTITION_ID", insertable = false, updatable = false))
	private JpaPidFk myResourcePid;

	/**
	 * This is here for sorting only, don't get or set this value
	 */
	@SuppressWarnings("unused")
	@Column(name = "RES_ID", insertable = false, nullable = false, updatable = false)
	private Long myResourceId;

	@Column(name = "RES_TYPE", length = ResourceTable.RESTYPE_LEN, nullable = false)
	private String myResourceType;

	@Column(name = "RES_VER", nullable = false)
	private Long myResourceVersion;

	@OneToMany(mappedBy = "myResourceHistory", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
	private Collection<ResourceHistoryTag> myTags;

	@Column(name = "RES_TEXT", length = Integer.MAX_VALUE - 1, nullable = true)
	@Lob()
	@OptimisticLock(excluded = true)
	private byte[] myResource;

	@Column(name = "RES_TEXT_VC", length = Length.LONG32, nullable = true)
	@OptimisticLock(excluded = true)
	private String myResourceTextVc;

	@Column(name = "RES_ENCODING", nullable = false, length = ENCODING_COL_LENGTH)
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@OptimisticLock(excluded = true)
	private ResourceEncodingEnum myEncoding;

	// TODO: This was added in 6.8.0 - In the future we should drop ResourceHistoryProvenanceEntity
	@Column(name = "SOURCE_URI", length = SOURCE_URI_LENGTH, nullable = true)
	private String mySourceUri;
	// TODO: This was added in 6.8.0 - In the future we should drop ResourceHistoryProvenanceEntity
	@Column(name = "REQUEST_ID", length = Constants.REQUEST_ID_LENGTH, nullable = true)
	private String myRequestId;

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

	@Transient
	private transient ResourceHistoryProvenanceEntity myNewHistoryProvenanceEntity;
	/**
	 * This is stored as an optimization to avoid needing to fetch ResourceTable
	 * to access the resource id.
	 */
	@Transient
	private transient String myTransientForcedId;

	/**
	 * Constructor
	 */
	public ResourceHistoryTable() {
		super();
	}

	public String getSourceUri() {
		return mySourceUri;
	}

	public void setSourceUri(String theSourceUri) {
		mySourceUri = theSourceUri;
	}

	public String getRequestId() {
		return myRequestId;
	}

	public void setRequestId(String theRequestId) {
		myRequestId = theRequestId;
	}

	@Override
	public String toString() {
		JpaPid resourceId = getResourceId();
		return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
				.append("resourceId", resourceId.getId())
				.append("partitionId", resourceId.getPartitionId())
				.append("resourceType", myResourceType)
				.append("resourceTypeId", getResourceTypeId())
				.append("resourceVersion", myResourceVersion)
				.append("pid", myId)
				.append("updated", getPublished())
				.toString();
	}

	public String getResourceTextVc() {
		return myResourceTextVc;
	}

	public void setResourceTextVc(String theResourceTextVc) {
		myResourceTextVc = theResourceTextVc;
	}

	public void addTag(ResourceTag theTag) {
		ResourceHistoryTag tag = new ResourceHistoryTag(this, theTag.getTag(), getPartitionId());
		tag.setResourceType(theTag.getResourceType());
		tag.setResourceTypeId(theTag.getResourceTypeId());
		getTags().add(tag);
	}

	@Override
	public ResourceHistoryTag addTag(TagDefinition theTag) {
		for (ResourceHistoryTag next : getTags()) {
			if (next.getTag().equals(theTag)) {
				return next;
			}
		}
		ResourceHistoryTag historyTag = new ResourceHistoryTag(this, theTag, getPartitionId());
		getTags().add(historyTag);
		return historyTag;
	}

	public ResourceEncodingEnum getEncoding() {
		return myEncoding;
	}

	public void setEncoding(ResourceEncodingEnum theEncoding) {
		myEncoding = theEncoding;
	}

	@Nonnull
	@Override
	public ResourceHistoryTablePk getId() {
		if (myId == null) {
			myId = new ResourceHistoryTablePk();
		}
		return myId;
	}

	/**
	 * Do not delete, required for java bean introspection
	 */
	public ResourceHistoryTablePk getMyId() {
		return getId();
	}

	/**
	 * Do not delete, required for java bean introspection
	 */
	public void setMyId(ResourceHistoryTablePk theId) {
		myId = theId;
	}

	public byte[] getResource() {
		return myResource;
	}

	public void setResource(byte[] theResource) {
		myResource = theResource;
	}

	@Override
	public JpaPid getResourceId() {
		initializeResourceId();
		JpaPid retVal = myResourcePid.toJpaPid();
		retVal.setVersion(myResourceVersion);
		retVal.setResourceType(myResourceType);
		if (retVal.getPartitionId() == null) {
			retVal.setPartitionId(myPartitionIdValue);
		}
		return retVal;
	}

	private void initializeResourceId() {
		if (myResourcePid == null) {
			myResourcePid = new JpaPidFk();
		}
	}

	public void setResourceId(Long theResourceId) {
		initializeResourceId();
		myResourcePid.setId(theResourceId);
	}

	@Override
	public String getResourceType() {
		return myResourceType;
	}

	@Override
	public String getFhirId() {
		return getIdDt().getIdPart();
	}

	public void setResourceType(String theResourceType) {
		myResourceType = theResourceType;
	}

	@Override
	public Short getResourceTypeId() {
		return myResourceTypeId;
	}

	public void setResourceTypeId(Short theResourceTypeId) {
		myResourceTypeId = theResourceTypeId;
	}

	public ResourceTypeEntity getResourceTypeEntity() {
		return myResourceTypeEntity;
	}

	@Override
	public Collection<ResourceHistoryTag> getTags() {
		if (myTags == null) {
			myTags = new ArrayList<>();
		}
		return myTags;
	}

	@Override
	public long getVersion() {
		return myResourceVersion;
	}

	public void setVersion(long theVersion) {
		myResourceVersion = theVersion;
	}

	@Override
	public boolean isDeleted() {
		return getDeleted() != null;
	}

	@Override
	public void setNotDeleted() {
		setDeleted(null);
	}

	@Override
	public JpaPid getPersistentId() {
		return getResourceId();
	}

	public ResourceTable getResourceTable() {
		return myResourceTable;
	}

	public void setResourceTable(ResourceTable theResourceTable) {
		myResourceTable = theResourceTable;
	}

	@Override
	public IdDt getIdDt() {
		// Avoid a join query if possible
		String resourceIdPart;
		if (getTransientForcedId() != null) {
			resourceIdPart = getTransientForcedId();
		} else {
			resourceIdPart = getResourceTable().getFhirId();
		}
		return new IdDt(getResourceType() + '/' + resourceIdPart + '/' + Constants.PARAM_HISTORY + '/' + getVersion());
	}

	/**
	 * Returns <code>true</code> if there is a populated resource text (i.e.
	 * either {@link #getResource()} or {@link #getResourceTextVc()} return a non null
	 * value.
	 */
	public boolean hasResource() {
		return myResource != null || myResourceTextVc != null;
	}

	/**
	 * This method creates a new HistoryProvenance entity, or might reuse the current one if we've
	 * already created one in the current transaction. This is because we can only increment
	 * the version once in a DB transaction (since hibernate manages that number) so creating
	 * multiple {@link ResourceHistoryProvenanceEntity} entities will result in a constraint error.
	 */
	public ResourceHistoryProvenanceEntity toProvenance() {
		if (myNewHistoryProvenanceEntity == null) {
			myNewHistoryProvenanceEntity = new ResourceHistoryProvenanceEntity();
		}
		return myNewHistoryProvenanceEntity;
	}

	public String getTransientForcedId() {
		return myTransientForcedId;
	}

	public void setTransientForcedId(String theTransientForcedId) {
		assert theTransientForcedId == null || !theTransientForcedId.contains("/")
				: "Invalid FHIR ID: " + theTransientForcedId;
		myTransientForcedId = theTransientForcedId;
	}

	public void setPartitionId(PartitionablePartitionId thePartitionablePartitionId) {
		if (thePartitionablePartitionId != null) {
			getId().setPartitionIdValue(thePartitionablePartitionId.getPartitionId());

			initializeResourceId();
			myResourcePid.setPartitionId(thePartitionablePartitionId.getPartitionId());

			myPartitionIdValue = thePartitionablePartitionId.getPartitionId();
			myPartitionDateValue = thePartitionablePartitionId.getPartitionDate();
		} else {
			getId().setPartitionIdValue(null);

			initializeResourceId();
			myResourcePid.setPartitionId(null);

			myPartitionIdValue = null;
			myPartitionDateValue = null;
		}
	}
}
