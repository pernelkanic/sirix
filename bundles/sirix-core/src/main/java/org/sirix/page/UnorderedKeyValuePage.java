/*
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 * <p>
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.sirix.page;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.Iterators;
import com.google.common.hash.Hashing;
import net.openhft.chronicle.bytes.Bytes;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sirix.access.ResourceConfiguration;
import org.sirix.api.PageReadOnlyTrx;
import org.sirix.api.PageTrx;
import org.sirix.exception.SirixIOException;
import org.sirix.index.IndexType;
import org.sirix.io.BytesUtils;
import org.sirix.node.interfaces.DataRecord;
import org.sirix.node.interfaces.NodePersistenter;
import org.sirix.node.interfaces.RecordSerializer;
import org.sirix.page.interfaces.KeyValuePage;
import org.sirix.settings.Constants;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import static org.sirix.node.Utils.getVarLong;
import static org.sirix.node.Utils.putVarLong;

/**
 * <p>
 * An UnorderedKeyValuePage stores a set of records, commonly nodes in an unordered data structure.
 * </p>
 * <p>
 * The page currently is not thread safe (might have to be for concurrent write-transactions)!
 * </p>
 */
public final class UnorderedKeyValuePage implements KeyValuePage<Long, DataRecord> {

  /**
   * The current revision.
   */
  private final int revision;

  /**
   * Determines if references to {@link OverflowPage}s have been added or not.
   */
  private boolean addedReferences;

  /**
   * References to overflow pages.
   */
  private final Map<Long, PageReference> references;

  /**
   * Key of record page. This is the base key of all contained nodes.
   */
  private final long recordPageKey;

  /**
   * The record-ID mapped to the records.
   */
  private final Map<Long, DataRecord> records;

  /**
   * Slots which have to be serialized.
   */
  private final Map<Long, byte[]> slots;

  /**
   * Dewey IDs to node key mapping.
   */
  private Map<byte[], Long> deweyIDs;

  /**
   * Sirix {@link PageReadOnlyTrx}.
   */
  private final PageReadOnlyTrx pageReadOnlyTrx;

  /**
   * The index type.
   */
  private final IndexType indexType;

  /**
   * Persistenter.
   */
  private final RecordSerializer recordPersister;

  /**
   * The resource configuration.
   */
  private final ResourceConfiguration resourceConfig;

  private volatile Bytes<ByteBuffer> bytes;

  private volatile byte[] hashCode;

  /**
   * Copy constructor.
   *
   * @param pageReadOnlyTrx the page read-only trx
   * @param pageToClone     the page to clone
   */
  public UnorderedKeyValuePage(final PageReadOnlyTrx pageReadOnlyTrx, final UnorderedKeyValuePage pageToClone) {
    addedReferences = pageToClone.addedReferences;
    references = pageToClone.references;
    recordPageKey = pageToClone.recordPageKey;
    records = pageToClone.records;
    slots = pageToClone.slots;
    deweyIDs = pageToClone.deweyIDs;
    this.pageReadOnlyTrx = pageReadOnlyTrx;
    indexType = pageToClone.indexType;
    recordPersister = pageToClone.recordPersister;
    resourceConfig = pageToClone.resourceConfig;
    revision = pageToClone.revision;
  }

  /**
   * Constructor which initializes a new {@link UnorderedKeyValuePage}.
   *
   * @param recordPageKey   base key assigned to this node page
   * @param indexType       the index type
   * @param pageReadOnlyTrx the page reading transaction
   */
  public UnorderedKeyValuePage(final @NonNegative long recordPageKey, final IndexType indexType,
      final PageReadOnlyTrx pageReadOnlyTrx) {
    // Assertions instead of checkNotNull(...) checks as it's part of the
    // internal flow.
    assert recordPageKey >= 0 : "recordPageKey must not be negative!";
    assert pageReadOnlyTrx != null : "The page reading trx must not be null!";

    references = new ConcurrentHashMap<>();
    this.recordPageKey = recordPageKey;
    records = new HashMap<>(Constants.NDP_NODE_COUNT);
    slots = new HashMap<>(Constants.NDP_NODE_COUNT);
    this.pageReadOnlyTrx = pageReadOnlyTrx;
    this.indexType = indexType;
    resourceConfig = pageReadOnlyTrx.getResourceManager().getResourceConfig();
    recordPersister = resourceConfig.recordPersister;
    deweyIDs = new HashMap<>(Constants.NDP_NODE_COUNT);
    this.revision = pageReadOnlyTrx.getRevisionNumber();
  }

  /**
   * Constructor which reads the {@link UnorderedKeyValuePage} from the storage.
   *
   * @param in          input bytes to read page from
   * @param pageReadTrx {@link PageReadOnlyTrx} implementation
   */
  UnorderedKeyValuePage(final Bytes<ByteBuffer> in, final PageReadOnlyTrx pageReadTrx) {
    recordPageKey = getVarLong(in);
    revision = in.readInt();
    resourceConfig = pageReadTrx.getResourceManager().getResourceConfig();
    recordPersister = resourceConfig.recordPersister;
    this.pageReadOnlyTrx = pageReadTrx;
    slots = new HashMap<>(Constants.NDP_NODE_COUNT);

    if (resourceConfig.areDeweyIDsStored && recordPersister instanceof NodePersistenter persistenter) {
      final int deweyIDSize = in.readInt();
      deweyIDs = new HashMap<>(deweyIDSize);
      records = new HashMap<>(deweyIDSize);
      byte[] optionalDeweyId = null;

      for (int index = 0; index < deweyIDSize; index++) {
        final byte[] deweyID = persistenter.deserializeDeweyID(in, optionalDeweyId, resourceConfig);

        optionalDeweyId = deweyID;

        if (deweyID != null) {
          deserializeRecordAndPutIntoMap(in, deweyID);
        }
      }
    } else {
      deweyIDs = new HashMap<>(Constants.NDP_NODE_COUNT);
      records = new HashMap<>(Constants.NDP_NODE_COUNT);
    }

    final var entriesBitmap = SerializationType.deserializeBitSet(in);
    final var overlongEntriesBitmap = SerializationType.deserializeBitSet(in);

    final int normalEntrySize = in.readInt();
    var setBit = -1;
    for (int index = 0; index < normalEntrySize; index++) {
      setBit = entriesBitmap.nextSetBit(setBit + 1);
      assert setBit >= 0;
      final long key = recordPageKey * Constants.NDP_NODE_COUNT + setBit;
      final int dataSize = in.readInt();
      final byte[] data = new byte[dataSize];
      in.read(data);
      var byteBufferBytes = Bytes.elasticByteBuffer();
      BytesUtils.doWrite(byteBufferBytes, data);
      final DataRecord record =
          recordPersister.deserialize(byteBufferBytes, key, null, this.pageReadOnlyTrx);
      byteBufferBytes.clear();
      byteBufferBytes = null;
      records.put(key, record);
    }

    final int overlongEntrySize = in.readInt();
    references = new LinkedHashMap<>(overlongEntrySize);
    setBit = -1;
    for (int index = 0; index < overlongEntrySize; index++) {
      setBit = overlongEntriesBitmap.nextSetBit(setBit + 1);
      assert setBit >= 0;
      final long key = recordPageKey * Constants.NDP_NODE_COUNT + setBit;
      final PageReference reference = new PageReference();
      reference.setKey(in.readLong());
      references.put(key, reference);
    }
    indexType = IndexType.getType(in.readByte());
  }

  public UnorderedKeyValuePage clearBytesAndHashCode() {
    if (bytes != null) {
      bytes.clear();
      bytes = null;
    }
    hashCode = null;
    return this;
  }

  private void deserializeRecordAndPutIntoMap(Bytes<ByteBuffer> in, byte[] deweyId) {
    final long key = getVarLong(in);
    final int dataSize = in.readInt();
    final byte[] data = new byte[dataSize];
    in.read(data);
    var byteBufferBytes = Bytes.elasticByteBuffer();
    BytesUtils.doWrite(byteBufferBytes, data);
    final DataRecord record =
        recordPersister.deserialize(byteBufferBytes, key, deweyId, pageReadOnlyTrx);
    byteBufferBytes.clear();
    byteBufferBytes = null;
    records.put(key, record);
  }

  @Override
  public long getPageKey() {
    return recordPageKey;
  }

  @Override
  public DataRecord getValue(final Long key) {
    assert key != null : "key must not be null!";
    DataRecord record = records.get(key);
    if (record == null) {
      byte[] data;
      try {
        final PageReference reference = references.get(key);
        if (reference != null && reference.getKey() != Constants.NULL_ID_LONG) {
          data = ((OverflowPage) pageReadOnlyTrx.getReader().read(reference, pageReadOnlyTrx)).getData();
        } else {
          return null;
        }
      } catch (final SirixIOException e) {
        return null;
      }
      var byteBufferBytes = Bytes.elasticByteBuffer();
      BytesUtils.doWrite(byteBufferBytes, data);
      record = recordPersister.deserialize(byteBufferBytes, key, null, null);
      byteBufferBytes.clear();
      byteBufferBytes = null;
      records.put(key, record);
    }
    return record;
  }

  @Override
  public void setRecord(final Long recordId, @NonNull final DataRecord record) {
    addedReferences = false;
    records.put(recordId, record);
  }

  public byte[] getHashCode() {
    return hashCode;
  }

  @Override
  public void serialize(final Bytes<ByteBuffer> out, final SerializationType type) {
    if (bytes != null) {
      out.write(bytes);
      return;
    }
    // Add references to overflow pages if necessary.
    addReferences();
    // Write page key.
    putVarLong(out, recordPageKey);
    // Write revision number.
    out.writeInt(revision);
    // Write dewey IDs.
    if (resourceConfig.areDeweyIDsStored && recordPersister instanceof NodePersistenter persistence) {
      // Write dewey IDs.
      out.writeInt(deweyIDs.size());
      final List<byte[]> ids = new ArrayList<>(deweyIDs.keySet());
      ids.sort(Comparator.comparingInt((byte[] sirixDeweyID) -> sirixDeweyID.length));
      final var iter = Iterators.peekingIterator(ids.iterator());
      byte[] id = null;
      if (iter.hasNext()) {
        id = iter.next();
        persistence.serializeDeweyID(out, id, null, resourceConfig);
        serializeDeweyRecord(id, out);
      }
      while (iter.hasNext()) {
        final var nextDeweyID = iter.next();
        persistence.serializeDeweyID(out, id, nextDeweyID, resourceConfig);
        serializeDeweyRecord(nextDeweyID, out);
        id = nextDeweyID;
      }
    }

    final var entriesBitmap = new BitSet(Constants.NDP_NODE_COUNT);
    final var entriesSortedByKey = slots.entrySet().stream().sorted(Entry.comparingByKey()).toList();
    for (final Entry<Long, byte[]> entry : entriesSortedByKey) {
      final var pageOffset = pageReadOnlyTrx.recordPageOffset(entry.getKey());
      entriesBitmap.set(pageOffset);
    }
    SerializationType.serializeBitSet(out, entriesBitmap);

    final var overlongEntriesBitmap = new BitSet(Constants.NDP_NODE_COUNT);
    final var overlongEntriesSortedByKey = references.entrySet().stream().sorted(Entry.comparingByKey()).toList();
    for (final Map.Entry<Long, PageReference> entry : overlongEntriesSortedByKey) {
      final var pageOffset = pageReadOnlyTrx.recordPageOffset(entry.getKey());
      overlongEntriesBitmap.set(pageOffset);
    }
    SerializationType.serializeBitSet(out, overlongEntriesBitmap);

    // Write normal entries.
    out.writeInt(entriesSortedByKey.size());
    for (final var entry : entriesSortedByKey) {
      final byte[] data = entry.getValue();
      final int length = data.length;
      out.writeInt(length);
      out.write(data);
    }

    // Write overlong entries.
    out.writeInt(overlongEntriesSortedByKey.size());
    for (final var entry : overlongEntriesSortedByKey) {
      // Write key in persistent storage.
      out.writeLong(entry.getValue().getKey());
    }

    out.writeByte(indexType.getID());
    hashCode = new byte[] {}; //Hashing.sha256().hashBytes(out.toByteArray()).asBytes();
    bytes = out;
  }

  private void serializeDeweyRecord(byte[] id, Bytes<ByteBuffer> out) {
    final long recordKey = deweyIDs.get(id);
    putVarLong(out, recordKey);
    final byte[] data = slots.get(recordKey);
    final int length = data.length;
    out.writeInt(length);
    out.write(data);
    slots.remove(recordKey);
  }

  @Override
  public String toString() {
    final MoreObjects.ToStringHelper helper = MoreObjects.toStringHelper(this).add("pagekey", recordPageKey);
    for (final DataRecord record : records.values()) {
      helper.add("record", record);
    }
    for (final PageReference reference : references.values()) {
      helper.add("reference", reference);
    }
    return helper.toString();
  }

  @Override
  public Set<Entry<Long, DataRecord>> entrySet() {
    return records.entrySet();
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(recordPageKey, records, references);
  }

  @Override
  public boolean equals(final @Nullable Object obj) {
    if (obj instanceof UnorderedKeyValuePage other) {
      return recordPageKey == other.recordPageKey && Objects.equal(records, other.records) && Objects.equal(references,
                                                                                                            other.references);
    }
    return false;
  }

  @Override
  public List<PageReference> getReferences() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void commit(@NonNull PageTrx pageWriteTrx) {
    addReferences();

    for (final PageReference reference : references.values()) {
      if (!(reference.getPage() == null && reference.getKey() == Constants.NULL_ID_LONG
          && reference.getLogKey() == Constants.NULL_ID_LONG)) {
        pageWriteTrx.commit(reference);
      }
    }
  }

  // Add references to OverflowPages.
  private void addReferences() {
    if (!addedReferences) {
      final var storeDeweyIDs = pageReadOnlyTrx.getResourceManager().getResourceConfig().areDeweyIDsStored;
      final var entries = records.values();

      if (storeDeweyIDs && recordPersister instanceof NodePersistenter && !entries.isEmpty()) {
        processEntries(entries);
        for (final var record : entries) {
          if (record.getDeweyIDAsBytes() != null && record.getNodeKey() != 0) {
            deweyIDs.put(record.getDeweyIDAsBytes(), record.getNodeKey());
          }
        }
      } else {
        processEntries(entries);
      }

      addedReferences = true;
    }
  }

  private void processEntries(final Collection<DataRecord> records) {
    var out = Bytes.elasticByteBuffer(30);
    for (final DataRecord record : records) {
      final var recordID = record.getNodeKey();
      if (slots.get(recordID) == null) {
        // Must be either a normal record or one which requires an overflow page.
        recordPersister.serialize(out, record, pageReadOnlyTrx);
        final var data = out.toByteArray();
        out.clear();
        if (data.length > PageConstants.MAX_RECORD_SIZE) {
          final var reference = new PageReference();
          reference.setPage(new OverflowPage(data));
          references.put(recordID, reference);
        } else {
          slots.put(recordID, data);
        }
      }
    }
    out = null;
  }

  @Override
  public Collection<DataRecord> values() {
    return records.values();
  }

  @Override
  public PageReference getOrCreateReference(int offset) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean setOrCreateReference(int offset, PageReference pageReference) {
    throw new UnsupportedOperationException();
  }

  @Override
  public PageReadOnlyTrx getPageReadOnlyTrx() {
    return pageReadOnlyTrx;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <C extends KeyValuePage<Long, DataRecord>> C newInstance(final long recordPageKey,
      @NonNull final IndexType indexType, @NonNull final PageReadOnlyTrx pageReadTrx) {
    return (C) new UnorderedKeyValuePage(recordPageKey, indexType, pageReadTrx);
  }

  @Override
  public IndexType getIndexType() {
    return indexType;
  }

  @Override
  public int size() {
    return records.size() + references.size();
  }

  @Override
  public void setPageReference(final Long key, @NonNull final PageReference reference) {
    assert key != null;
    references.put(key, reference);
  }

  @Override
  public Set<Entry<Long, PageReference>> referenceEntrySet() {
    return references.entrySet();
  }

  @Override
  public PageReference getPageReference(final Long key) {
    assert key != null;
    return references.get(key);
  }

  @Override
  public int getRevision() {
    return revision;
  }
}
