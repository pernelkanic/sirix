/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the
 * names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.access;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sirix.access.conf.ResourceConfiguration;
import org.sirix.access.conf.ResourceConfiguration.Indexes;
import org.sirix.api.PageReadTrx;
import org.sirix.api.Session;
import org.sirix.cache.NodePageContainer;
import org.sirix.cache.TransactionLogCache;
import org.sirix.cache.TransactionLogPageCache;
import org.sirix.exception.SirixException;
import org.sirix.exception.SirixIOException;
import org.sirix.io.Reader;
import org.sirix.node.DeletedNode;
import org.sirix.node.Kind;
import org.sirix.node.interfaces.NodeBase;
import org.sirix.page.IndirectPage;
import org.sirix.page.NamePage;
import org.sirix.page.NodePage;
import org.sirix.page.PageKind;
import org.sirix.page.PageReference;
import org.sirix.page.PathSummaryPage;
import org.sirix.page.RevisionRootPage;
import org.sirix.page.UberPage;
import org.sirix.page.ValuePage;
import org.sirix.page.interfaces.Page;
import org.sirix.settings.Constants;
import org.sirix.settings.Revisioning;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * <h1>PageReadTransaction</h1>
 * 
 * <p>
 * Page reading transaction. The only thing shared amongst transactions is the
 * page cache. Everything else is exclusive to this transaction. It is required
 * that only a single thread has access to this transaction.
 * </p>
 */
final class PageReadTrxImpl implements PageReadTrx {

	/** Page reader exclusively assigned to this transaction. */
	private final Reader mPageReader;

	/** Uber page this transaction is bound to. */
	private final UberPage mUberPage;

	/** Cached name page of this revision. */
	private final RevisionRootPage mRootPage;

	/** Internal reference to node cache. */
	private final LoadingCache<Long, NodePageContainer> mNodeCache;

	/** Internal reference to path cache. */
	private final LoadingCache<Long, NodePageContainer> mPathCache;

	/** Internal reference to value cache. */
	private final LoadingCache<Long, NodePageContainer> mValueCache;

	/** Internal reference to page cache. */
	private final LoadingCache<Long, Page> mPageCache;

	/** {@link SessionImpl} reference. */
	protected final SessionImpl mSession;

	/** {@link NamePage} reference. */
	private final NamePage mNamePage;

	/** Determines if page reading transaction is closed or not. */
	private boolean mClosed;

	/** Indexes to read. */
	private final Set<Indexes> mIndexes;

	/**
	 * Optional page transaction log, dependent on the fact, if the log hasn't
	 * been completely transferred into the data file.
	 */
	private final Optional<TransactionLogPageCache> mPageLog;

	/**
	 * Optional path transaction log, dependent on the fact, if the log hasn't
	 * been completely transferred into the data file.
	 */
	private final Optional<TransactionLogCache> mPathLog;

	/**
	 * Optional value transaction log, dependent on the fact, if the log hasn't
	 * been completely transferred into the data file.
	 */
	private final Optional<TransactionLogCache> mValueLog;

	/**
	 * Optional node transaction log, dependent on the fact, if the log hasn't
	 * been completely transferred into the data file.
	 */
	private final Optional<TransactionLogCache> mNodeLog;

	/**
	 * {@link ResourceConfiguration} instance.
	 */
	final ResourceConfiguration mResourceConfig;

	/**
	 * Standard constructor.
	 * 
	 * @param session
	 *          current {@link SessionImpl} instance
	 * @param uberPage
	 *          {@link UberPage} to start reading from
	 * @param revision
	 *          key of revision to read from uber page
	 * @param reader
	 *          reader to read stored pages for this transaction
	 * @param persistentCache
	 *          optional persistent cache
	 * @throws SirixIOException
	 *           if reading of the persistent storage fails
	 */
	PageReadTrxImpl(final @Nonnull SessionImpl session,
			final @Nonnull UberPage uberPage, final @Nonnegative int revision,
			final @Nonnull Reader reader) throws SirixIOException {
		checkArgument(revision >= 0, "Revision must be >= 0!");
		mIndexes = session.mResourceConfig.mIndexes;
		mResourceConfig = session.mResourceConfig;

		// mPageLog = Optional.<TransactionLogPageCache> absent();
		// mNodeLog = Optional.<TransactionLogCache> absent();
		// mPathLog = Optional.<TransactionLogCache> absent();
		// mValueLog = Optional.<TransactionLogCache> absent();

		// In memory caches from data directory.
		// =========================================================
		mNodeCache = CacheBuilder.newBuilder().maximumSize(1000)
				.expireAfterWrite(5000, TimeUnit.SECONDS)
				.expireAfterAccess(5000, TimeUnit.SECONDS).concurrencyLevel(1)
				.build(new CacheLoader<Long, NodePageContainer>() {
					public NodePageContainer load(final Long pKey) throws SirixException {
						final NodePageContainer container = mNodeLog.isPresent() ? mNodeLog
								.get().get(pKey) : NodePageContainer.EMPTY_INSTANCE;
						if (container.equals(NodePageContainer.EMPTY_INSTANCE)) {
							return getNodeFromPage(pKey, PageKind.NODEPAGE);
						} else {
							return container;
						}
					}
				});
		final CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder()
				.concurrencyLevel(1).maximumSize(20);
		if (mIndexes.contains(Indexes.PATH)) {
			mPathCache = builder.build(new CacheLoader<Long, NodePageContainer>() {
				public NodePageContainer load(final Long pKey) throws SirixException {
					final NodePageContainer container = mPathLog.isPresent() ? mPathLog
							.get().get(pKey) : NodePageContainer.EMPTY_INSTANCE;
					if (container.equals(NodePageContainer.EMPTY_INSTANCE)) {
						return getNodeFromPage(pKey, PageKind.PATHSUMMARYPAGE);
					} else {
						return container;
					}
				}
			});
		} else {
			mPathCache = null;
		}
		if (mIndexes.contains(Indexes.VALUE)) {
			mValueCache = builder.build(new CacheLoader<Long, NodePageContainer>() {
				public NodePageContainer load(final Long pKey) throws SirixException {
					final NodePageContainer container = mValueLog.isPresent() ? mValueLog
							.get().get(pKey) : null;
					if (NodePageContainer.EMPTY_INSTANCE.equals(container)) {
						return getNodeFromPage(pKey, PageKind.VALUEPAGE);
					} else {
						return container;
					}
				}
			});
		} else {
			mValueCache = null;
		}

		final CacheBuilder<Object, Object> pageCacheBuilder = CacheBuilder
				.newBuilder();
		// if (persistentCache.isPresent()) {
		// pageCacheBuilder.removalListener(new RemovalListener<Long, Page>() {
		// @Override
		// public void onRemoval(final RemovalNotification<Long, Page> pRemoval) {
		// final Page page = pRemoval.getValue();
		// if (page.isDirty()) {
		// persistentCache.get().put(pRemoval.getKey(), page);
		// }
		// }
		// });
		// }

		final PageReadTrxImpl impl = this;
		mPageCache = pageCacheBuilder.build(new CacheLoader<Long, Page>() {
			public Page load(final Long pKey) throws SirixException {
				final Page page = mPageLog.isPresent() ? mPageLog.get().get(pKey)
						: null;
				if (page == null) {
					return mPageReader.read(pKey, impl).setDirty(true);
				} else {
					return page;
				}
			}
		});
		mSession = checkNotNull(session);
		mPageReader = checkNotNull(reader);
		mUberPage = checkNotNull(uberPage);

		// Transaction logs which might have to be read because the data hasn't been
		// commited to the data-file.
		// =======================================================
		final File commitFile = session.mCommitFile;
		final boolean doesExist = commitFile != null && commitFile.exists();
		mPageLog = doesExist ? Optional.of(new TransactionLogPageCache(
				session.mResourceConfig.mPath, revision, "page", this)) : Optional
				.<TransactionLogPageCache> absent();
		mNodeLog = doesExist ? Optional.of(new TransactionLogCache(
				session.mResourceConfig.mPath, revision, "node", this)) : Optional
				.<TransactionLogCache> absent();
		if (mIndexes.contains(Indexes.PATH)) {
			mPathLog = doesExist ? Optional.of(new TransactionLogCache(
					session.mResourceConfig.mPath, revision, "path", this)) : Optional
					.<TransactionLogCache> absent();
		} else {
			mPathLog = Optional.<TransactionLogCache> absent();
		}
		if (mIndexes.contains(Indexes.VALUE)) {
			mValueLog = doesExist ? Optional.of(new TransactionLogCache(
					session.mResourceConfig.mPath, revision, "value", this)) : Optional
					.<TransactionLogCache> absent();
		} else {
			mValueLog = Optional.<TransactionLogCache> absent();
		}
		// Load revision root.
		mRootPage = loadRevRoot(revision);
		assert mRootPage != null : "root page must not be null!";
		// First create revision tree if needed.
		mRootPage.createNodeTree(this);
		if (mIndexes.contains(Indexes.PATH)) {
			// Create path summary tree if needed.
			getPathSummaryPage(mRootPage);
			mRootPage.createPathSummaryTree(this);
		}
		if (mIndexes.contains(Indexes.VALUE)) {
			// Create value tree if needed.
			getValuePage(mRootPage);
			mRootPage.createValueTree(this);
		}
		mNamePage = getNamePage();
		mClosed = false;
	}

	@Override
	public Session getSession() {
		assertNotClosed();
		return mSession;
	}

	/**
	 * Make sure that the transaction is not yet closed when calling this method.
	 */
	final void assertNotClosed() {
		if (mClosed) {
			throw new IllegalStateException("Transaction is already closed.");
		}
	}

	@Override
	public Optional<NodeBase> getNode(final @Nonnegative long nodeKey,
			final @Nonnull PageKind pageKind) throws SirixIOException {
		checkArgument(nodeKey >= 0);
		checkNotNull(pageKind);
		assertNotClosed();

		final long nodePageKey = nodePageKey(nodeKey);
		// final int nodePageOffset = nodePageOffset(pNodeKey);

		NodePageContainer cont;
		try {
			switch (pageKind) {
			case NODEPAGE:
				cont = mNodeCache.get(nodePageKey);
				break;
			case PATHSUMMARYPAGE:
				cont = mPathCache.get(nodePageKey);
				break;
			case VALUEPAGE:
				cont = mValueCache.get(nodePageKey);
				break;
			default:
				throw new IllegalStateException();
			}
		} catch (final ExecutionException e) {
			throw new SirixIOException(e);
		}

		if (cont.equals(NodePageContainer.EMPTY_INSTANCE)) {
			return Optional.<NodeBase> absent();
		}

		final NodeBase retVal = cont.getComplete().getNode(nodeKey);
		return Optional.fromNullable(checkItemIfDeleted(retVal));
	}

	/**
	 * Method to check if an {@link NodeBase} is deleted.
	 * 
	 * @param toCheck
	 *          node to check
	 * @return the {@code node} if it is valid, {@code null} otherwise
	 */
	final NodeBase checkItemIfDeleted(final @Nullable NodeBase toCheck) {
		if (toCheck instanceof DeletedNode) {
			return null;
		} else {
			return toCheck;
		}
	}

	@Override
	public String getName(final int nameKey, final @Nonnull Kind nodeKind) {
		assertNotClosed();
		return mNamePage.getName(nameKey, nodeKind);
	}

	@Override
	public final byte[] getRawName(final int pNameKey,
			final @Nonnull Kind pNodeKind) {
		assertNotClosed();
		return mNamePage.getRawName(pNameKey, pNodeKind);
	}

	@Override
	public void clearCaches() {
		assertNotClosed();

		if (mIndexes.contains(Indexes.PATH)) {
			mPathCache.invalidateAll();
		}
		if (mIndexes.contains(Indexes.VALUE)) {
			mValueCache.invalidateAll();
		}
		mNodeCache.invalidateAll();
		mPageCache.invalidateAll();

		if (mPathLog.isPresent()) {
			mPathLog.get().clear();
		}
		if (mValueLog.isPresent()) {
			mValueLog.get().clear();
		}
		if (mNodeLog.isPresent()) {
			mNodeLog.get().clear();
		}
		if (mPageLog.isPresent()) {
			mPageLog.get().clear();
		}
	}

	/**
	 * Close caches.
	 */
	void closeCaches() {
		if (mPathLog.isPresent()) {
			mPathLog.get().close();
		}
		if (mValueLog.isPresent()) {
			mValueLog.get().close();
		}
		if (mNodeLog.isPresent()) {
			mNodeLog.get().close();
		}
		if (mPageLog.isPresent()) {
			mPageLog.get().close();
		}
	}

	/**
	 * Get revision root page belonging to revision key.
	 * 
	 * @param revisionKey
	 *          key of revision to find revision root page for
	 * @return revision root page of this revision key
	 * 
	 * @throws SirixIOException
	 *           if something odd happens within the creation process
	 */
	final RevisionRootPage loadRevRoot(final @Nonnegative int revisionKey)
			throws SirixIOException {
		checkArgument(
				revisionKey >= 0 && revisionKey <= mSession.getLastRevisionNumber(),
				"%s must be >= 0 and <= last stored revision (%s)!", revisionKey,
				mSession.getLastRevisionNumber());

		// The indirect page reference either fails horribly or returns a non null
		// instance.
		final PageReference ref = dereferenceLeafOfTree(
				mUberPage.getIndirectPageReference(), revisionKey, PageKind.UBERPAGE);
		RevisionRootPage page = (RevisionRootPage) ref.getPage();

		// If there is no page, get it from the storage and cache it.
		if (page == null) {
			try {
				page = (RevisionRootPage) mPageCache.get(ref.getKey());
			} catch (final ExecutionException e) {
				throw new SirixIOException(e.getCause());
			}
		}

		// Get revision root page which is the leaf of the indirect tree.
		return page;
	}

	/**
	 * Initialize NamePage.
	 * 
	 * @throws SirixIOException
	 *           if an I/O error occurs
	 */
	private final NamePage getNamePage() throws SirixIOException {
		assertNotClosed();
		final PageReference ref = mRootPage.getNamePageReference();
		if (ref.getPage() == null) {
			try {
				ref.setPage(mPageCache.get(ref.getKey()));
			} catch (final ExecutionException e) {
				throw new SirixIOException(e);
			}
		}
		ref.setPageKind(PageKind.NAMEPAGE);
		return (NamePage) ref.getPage();
	}

	/**
	 * Initialize PathSummaryPage.
	 * 
	 * @throws SirixIOException
	 *           if an I/O error occurs
	 */
	private final PathSummaryPage getPathSummaryPage(
			final @Nonnull RevisionRootPage page) throws SirixIOException {
		assertNotClosed();
		final PageReference ref = page.getPathSummaryPageReference();
		if (ref.getPage() == null) {
			try {
				ref.setPage(mPageCache.get(ref.getKey()));
			} catch (final ExecutionException e) {
				throw new SirixIOException(e);
			}
		}
		ref.setPageKind(PageKind.PATHSUMMARYPAGE);
		return (PathSummaryPage) ref.getPage();
	}

	/**
	 * Initialize ValuePage.
	 * 
	 * @throws SirixIOException
	 *           if an I/O error occurs
	 */
	private final ValuePage getValuePage(final @Nonnull RevisionRootPage page)
			throws SirixIOException {
		assertNotClosed();
		final PageReference ref = page.getValuePageReference();
		if (ref.getPage() == null) {
			try {
				ref.setPage(mPageCache.get(ref.getKey()));
			} catch (final ExecutionException e) {
				throw new SirixIOException(e);
			}
		}
		ref.setPageKind(PageKind.VALUEPAGE);
		return (ValuePage) ref.getPage();
	}

	@Override
	public final UberPage getUberPage() {
		return mUberPage;
	}

	/**
	 * Dereference node page reference and get all leaves, the {@link NodePage}s
	 * from the revision-trees.
	 * 
	 * @param nodePageKey
	 *          key of node page
	 * @return dereferenced pages
	 * 
	 * @throws SirixIOException
	 *           if an I/O-error occurs within the creation process
	 */
	final NodePage[] getSnapshotPages(final @Nonnegative long nodePageKey,
			final @Nonnull PageKind page) throws SirixIOException {
		assert nodePageKey >= 0;
		assert page != null;
		// TODO:
		final List<PageReference> refs = new ArrayList<>();
		final Set<Long> keys = new HashSet<>();
		final ResourceConfiguration config = mSession.getResourceConfig();
		final int revsToRestore = config.mRevisionsToRestore;
		for (int i = mRootPage.getRevision(); i >= 0; i--) {
			final PageReference tmpRef = getPageReference(loadRevRoot(i), page);
			final PageReference ref = dereferenceLeafOfTree(tmpRef, nodePageKey, page);
			if (ref != null
					&& (ref.getPage() != null || ref.getKey() != Constants.NULL_ID)) {
				if (ref.getKey() == Constants.NULL_ID || (!keys.contains(ref.getKey()))) {
					refs.add(ref);
					if (ref.getKey() != Constants.NULL_ID) {
						keys.add(ref.getKey());
					}
				}
				if (refs.size() == revsToRestore
						|| config.mRevisionKind == Revisioning.FULL
						|| (config.mRevisionKind == Revisioning.DIFFERENTIAL && refs.size() == 2)) {
					break;
				}
				if (config.mRevisionKind == Revisioning.DIFFERENTIAL) {
					if (i - revsToRestore >= 0) {
						i = i - revsToRestore + 1;
					} else if (i == 0) {
						break;
					} else {
						i = 1;
					}
				}
			} else {
				break;
			}
		}

		// Afterwards read the NodePages if they are not dereferences...
		final NodePage[] pages = new NodePage[refs.size()];
		for (int i = 0; i < pages.length; i++) {
			final PageReference ref = refs.get(i);
			pages[i] = (NodePage) ref.getPage();
			if (pages[i] == null) {
				pages[i] = (NodePage) mPageReader.read(ref.getKey(), this);
			}
			ref.setPageKind(page);
		}
		return pages;
	}

	/**
	 * Get the page reference which points to the right subtree (usual nodes, path
	 * summary nodes, value index nodes).
	 * 
	 * @param revisionRoot
	 *          {@link RevisionRootPage} instance
	 * @param pPage
	 *          the page type to determine the right subtree
	 */
	PageReference getPageReference(final @Nonnull RevisionRootPage revisionRoot,
			final @Nonnull PageKind pPage) throws SirixIOException {
		assert revisionRoot != null;
		PageReference ref = null;
		switch (pPage) {
		case NODEPAGE:
			ref = revisionRoot.getIndirectPageReference();
			break;
		case VALUEPAGE:
			ref = getValuePage(revisionRoot).getIndirectPageReference();
			break;
		case PATHSUMMARYPAGE:
			ref = getPathSummaryPage(revisionRoot).getIndirectPageReference();
			break;
		default:
			throw new IllegalStateException(
					"Only defined for node pages and path summary pages!");
		}
		return ref;
	}

	/**
	 * Dereference indirect page reference.
	 * 
	 * @param reference
	 *          reference to dereference
	 * @return dereferenced page
	 * 
	 * @throws SirixIOException
	 *           if something odd happens within the creation process.
	 */
	final IndirectPage dereferenceIndirectPage(
			final @Nonnull PageReference reference) throws SirixIOException {
		final Page tmpPage = reference.getPage();
		if (tmpPage == null || tmpPage instanceof IndirectPage) {
			IndirectPage page = (IndirectPage) tmpPage;

			// If there is no page, get it from the storage and cache it.
			if (page == null && reference.getKey() != Constants.NULL_ID) {
				try {
					page = (IndirectPage) mPageCache.get(reference.getKey());
				} catch (final ExecutionException e) {
					throw new SirixIOException(e);
				}
			}

			reference.setPage(page);
			return page;
		} else {
			throw new IllegalArgumentException(
					"Must be a reference to an indirect page!");
		}
	}

	/**
	 * Find reference pointing to leaf page of an indirect tree.
	 * 
	 * @param startReference
	 *          start reference pointing to the indirect tree
	 * @param key
	 *          key to look up in the indirect tree
	 * @return reference denoted by key pointing to the leaf page
	 * 
	 * @throws SirixIOException
	 *           if an I/O error occurs
	 */
	@Nullable
	final PageReference dereferenceLeafOfTree(
			final @Nonnull PageReference startReference, final @Nonnegative long key,
			final @Nonnull PageKind pPage) throws SirixIOException {

		// Initial state pointing to the indirect page of level 0.
		PageReference reference = checkNotNull(startReference);
		int offset = 0;
		long levelKey = key;
		final int[] inpLevelPageCountExp = mUberPage.getPageCountExp(pPage);

		// Iterate through all levels.
		for (int level = 0, height = inpLevelPageCountExp.length; level < height; level++) {
			offset = (int) (levelKey >> inpLevelPageCountExp[level]);
			levelKey -= offset << inpLevelPageCountExp[level];
			final Page derefPage = dereferenceIndirectPage(reference);
			if (derefPage == null) {
				reference = null;
				break;
			} else {
				try {
					reference = derefPage.getReference(offset);
				} catch (final IndexOutOfBoundsException e) {
					throw new SirixIOException("Node key isn't supported, it's too big!");
				}
			}
		}

		// Return reference to leaf of indirect tree.
		if (reference != null) {
			reference.setPageKind(pPage);
		}
		return reference;
	}

	/**
	 * Calculate node page key from a given node key.
	 * 
	 * @param pNodeKey
	 *          node key to find node page key for
	 * @return node page key
	 */
	final long nodePageKey(final @Nonnegative long pNodeKey) {
		checkArgument(pNodeKey >= 0, "pNodeKey must not be negative!");
		return pNodeKey >> Constants.NDP_NODE_COUNT_EXPONENT;
	}

	// /**
	// * Calculate node page offset for a given node key.
	// *
	// * @param pNodeKey
	// * node key to find offset for
	// * @return offset into node page
	// */
	// final int nodePageOffset(final @Nonnegative long pNodeKey) {
	// checkArgument(pNodeKey >= 0, "pNodeKey must not be negative!");
	// final long shift =
	// ((pNodeKey >> IConstants.NDP_NODE_COUNT_EXPONENT) <<
	// IConstants.NDP_NODE_COUNT_EXPONENT);
	// return (int)(pNodeKey - shift);
	// }

	@Override
	public RevisionRootPage getActualRevisionRootPage() throws SirixIOException {
		assertNotClosed();
		return mRootPage;
	}

	@Override
	public String toString() {
		return Objects.toStringHelper(this).add("Session: ", mSession)
				.add("PageReader: ", mPageReader).add("UberPage: ", mUberPage)
				.add("RevRootPage: ", mRootPage).toString();
	}

	@Override
	public NodePageContainer getNodeFromPage(final @Nonnegative long nodePageKey,
			final @Nonnull PageKind pPage) throws SirixIOException {
		assertNotClosed();
		final NodePage[] revs = getSnapshotPages(nodePageKey, pPage);
		if (revs.length == 0) {
			return NodePageContainer.EMPTY_INSTANCE;
		}

		final int mileStoneRevision = mResourceConfig.mRevisionsToRestore;
		final Revisioning revisioning = mResourceConfig.mRevisionKind;
		final NodePage completePage = revisioning.combineNodePages(revs,
				mileStoneRevision, this);
		return new NodePageContainer(completePage);
	}

	@Override
	public void close() throws SirixIOException {
		if (!mClosed) {
			closeCaches();
			mPageReader.close();

			mClosed = true;
		}
	}

	@Override
	public int getNameCount(int pKey, @Nonnull Kind pKind) {
		assertNotClosed();
		return mNamePage.getCount(pKey, pKind);
	}

	@Override
	public boolean isClosed() {
		return mClosed;
	}

	@Override
	public int getRevisionNumber() {
		assertNotClosed();
		return mRootPage.getRevision();
	}

	@Override
	public Page getFromPageCache(final @Nonnegative long pKey)
			throws SirixIOException {
		assertNotClosed();
		Page retVal = null;
		try {
			retVal = mPageCache.get(pKey);
		} catch (ExecutionException e) {
			throw new SirixIOException(e.getCause());
		}
		return retVal;
	}

	@Override
	public void putPageCache(final @Nonnull TransactionLogPageCache pPageLog) {
		assertNotClosed();
		pPageLog.putAll(mPageCache.asMap());
	}
}