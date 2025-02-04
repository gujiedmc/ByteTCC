/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytetcc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.jdbc.RecoveredResource;
import org.bytesoft.bytejta.supports.resource.LocalXAResourceDescriptor;
import org.bytesoft.bytejta.supports.resource.RemoteResourceDescriptor;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.CommitRequiredException;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionLock;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.archive.TransactionArchive;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.recovery.TransactionRecoveryCallback;
import org.bytesoft.transaction.recovery.TransactionRecoveryListener;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.remote.RemoteSvc;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionRecoveryImpl
		implements TransactionRecovery, TransactionRecoveryListener, CompensableBeanFactoryAware, CompensableEndpointAware {
	static final Logger logger = LoggerFactory.getLogger(TransactionRecoveryImpl.class);

	static final long SECOND_MILLIS = 1000L;

	@javax.inject.Inject
	protected CompensableBeanFactory beanFactory;
	protected String endpoint;
	protected transient boolean statefully;
	protected volatile boolean initialized;

	protected final Map<TransactionXid, Transaction> recovered = new HashMap<TransactionXid, Transaction>();

	public void onRecovery(Transaction transaction) {
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid xid = transactionContext.getXid();

		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());

		this.recovered.put(globalXid, transaction);
	}

	public synchronized void startRecovery() {
		this.fireTransactionStartRecovery();
		this.fireCompensableStartRecovery();

		// timingRecovery should be executed after initialization
		this.initialized = true;
	}

	protected void fireTransactionStartRecovery() {
		TransactionRecovery transactionRecovery = this.beanFactory.getTransactionRecovery();
		transactionRecovery.startRecovery();
	}

	protected void fireCompensableStartRecovery() {
		final TransactionRepository transactionRepository = this.beanFactory.getCompensableRepository();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		compensableLogger.recover(new TransactionRecoveryCallback() {
			public void recover(TransactionArchive archive) {
				this.recover((org.bytesoft.compensable.archive.TransactionArchive) archive);
			}

			public void recover(org.bytesoft.compensable.archive.TransactionArchive archive) {
				XidFactory transactionXidFactory = beanFactory.getTransactionXidFactory();

				CompensableTransactionImpl transaction = reconstruct(archive);
				TransactionContext transactionContext = transaction.getTransactionContext();

				TransactionXid compensableXid = transactionContext.getXid();
				if (transactionContext.isCompensable() == false) {
					TransactionXid transactionXid = transactionXidFactory
							.createGlobalXid(compensableXid.getGlobalTransactionId());
					Transaction tx = recovered.get(transactionXid);
					if (tx != null) {
						tx.setTransactionalExtra(transaction);
						transaction.setTransactionalExtra(tx); // TODO different thread
					}
				} else {
					recoverStatusIfNecessary(transaction);
				} // end-if (transactionContext.isCoordinator())

				transactionRepository.putTransaction(compensableXid, transaction);
				transactionRepository.putErrorTransaction(compensableXid, transaction);
			}
		});

		CompensableCoordinator compensableCoordinator = //
				(CompensableCoordinator) this.beanFactory.getCompensableNativeParticipant();
		compensableCoordinator.markParticipantReady();
	}

	public CompensableTransactionImpl reconstruct(TransactionArchive transactionArchive) {
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();

		org.bytesoft.compensable.archive.TransactionArchive archive = (org.bytesoft.compensable.archive.TransactionArchive) transactionArchive;
		int transactionStatus = archive.getCompensableStatus();

		TransactionContext transactionContext = new TransactionContext();
		transactionContext.setCompensable(true);
		transactionContext.setStatefully(this.statefully);
		transactionContext.setCoordinator(archive.isCoordinator());
		transactionContext.setPropagated(archive.isPropagated());
		transactionContext.setCompensating(
				Status.STATUS_ACTIVE != transactionStatus && Status.STATUS_MARKED_ROLLBACK != transactionStatus);
		transactionContext.setRecoveried(true);
		transactionContext.setXid(xidFactory.createGlobalXid(archive.getXid().getGlobalTransactionId()));
		transactionContext.setPropagatedBy(transactionArchive.getPropagatedBy());
		transactionContext.setRecoveredTimes(transactionArchive.getRecoveredTimes());
		transactionContext.setCreatedTime(transactionArchive.getRecoveredAt());

		CompensableTransactionImpl transaction = new CompensableTransactionImpl(transactionContext);
		transaction.setBeanFactory(this.beanFactory);
		transaction.setTransactionVote(archive.getVote());
		transaction.setTransactionStatus(transactionStatus);
		transaction.setVariables(archive.getVariables());

		List<XAResourceArchive> participantList = archive.getRemoteResources();
		for (int i = 0; i < participantList.size(); i++) {
			XAResourceArchive participantArchive = participantList.get(i);
			XAResourceDescriptor descriptor = participantArchive.getDescriptor();

			transaction.getParticipantArchiveList().add(participantArchive);
			if (RemoteResourceDescriptor.class.isInstance(descriptor)) {
				RemoteResourceDescriptor resourceDescriptor = (RemoteResourceDescriptor) descriptor;
				RemoteCoordinator remoteCoordinator = resourceDescriptor.getDelegate();
				RemoteSvc remoteSvc = CommonUtils.getRemoteSvc(remoteCoordinator.getRemoteNode());
				transaction.getParticipantArchiveMap().put(remoteSvc, participantArchive);
			} // end-if (RemoteResourceDescriptor.class.isInstance(descriptor))
		}

		List<CompensableArchive> compensableList = archive.getCompensableResourceList();
		for (int i = 0; i < compensableList.size(); i++) {
			CompensableArchive compensableArchive = compensableList.get(i);
			transaction.getCompensableArchiveList().add(compensableArchive);
		}

		return transaction;
	}

	public void recoverStatusIfNecessary(Transaction transaction) {
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
		CompensableTransactionImpl compensable = (CompensableTransactionImpl) transaction;
		List<CompensableArchive> archiveList = compensable.getCompensableArchiveList();

		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		Map<TransactionBranchKey, Boolean> triedMap = new HashMap<TransactionBranchKey, Boolean>();
		int triedNumber = 0;
		int unTriedNumber = 0;
		int unknownNumber = 0;
		for (int i = 0; i < archiveList.size(); i++) {
			CompensableArchive archive = archiveList.get(i);

			TransactionBranchKey recordKey = new TransactionBranchKey();
			recordKey.xid = archive.getTransactionXid();
			recordKey.resource = archive.getTransactionResourceKey();

			if (archive.isTried()) {
				triedNumber++;

				triedMap.put(recordKey, Boolean.TRUE);
			} else if (StringUtils.isBlank(recordKey.resource)) {
				unknownNumber++;
				logger.warn(
						"There is no valid resource participated in the trying branch transaction, the status of the branch transaction is unknown!");
			} else if (triedMap.containsKey(recordKey)) {
				Boolean tried = triedMap.get(recordKey);
				if (Boolean.TRUE.equals(tried)) {
					archive.setTried(true);
					triedNumber++;

					compensableLogger.updateCompensable(archive);
				} else {
					unTriedNumber++;
				}
			} else {
				Boolean tried = this.calculateCompensableTried(recordKey);
				if (Boolean.TRUE.equals(tried)) {
					archive.setTried(true);
					triedNumber++;

					triedMap.put(recordKey, Boolean.TRUE);
					compensableLogger.updateCompensable(archive);
				} else {
					unTriedNumber++;
					triedMap.put(recordKey, tried);
				}
			}
		} // end-for

		if (transactionContext.isCoordinator()) {
			if (triedNumber > 0 && unTriedNumber > 0) {
				transaction.setTransactionStatus(Status.STATUS_PREPARING);
				compensableLogger.updateTransaction(compensable.getTransactionArchive());
			} else if (triedNumber > 0 && unknownNumber > 0) {
				switch (transaction.getTransactionStatus()) {
				case Status.STATUS_PREPARING:
				case Status.STATUS_PREPARED:
				case Status.STATUS_COMMITTED:
				case Status.STATUS_ROLLEDBACK:
				case Status.STATUS_ROLLING_BACK:
				case Status.STATUS_COMMITTING:
					break; // ignore
				default:
					transaction.setTransactionStatus(Status.STATUS_PREPARING);
					compensableLogger.updateTransaction(compensable.getTransactionArchive());
				}
			} else if (triedNumber > 0 && transactionContext.isPropagated() == false) {
				transaction.setTransactionStatus(Status.STATUS_PREPARED);
				compensableLogger.updateTransaction(compensable.getTransactionArchive());
			}
		} // end-if (transactionContext.isCoordinator())

	}

	protected Boolean calculateCompensableTried(TransactionBranchKey recordKey) {
		if (StringUtils.isBlank(recordKey.resource)) {
			logger.warn(
					"There is no valid resource participated in the trying branch transaction, the status of the branch transaction is unknown!");
			return null;
		}

		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();

		Xid transactionXid = recordKey.xid;
		try {
			LocalXAResourceDescriptor descriptor = //
					(LocalXAResourceDescriptor) resourceDeserializer.deserialize(recordKey.resource);
			RecoveredResource resource = (RecoveredResource) descriptor.getDelegate();
			resource.recoverable(transactionXid);
			return true;
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XAER_NOTA:
				return false;
			case XAException.XAER_RMERR:
				logger.warn(
						"The database table 'bytejta' cannot found, the status of the trying branch transaction is unknown!");
				break;
			case XAException.XAER_RMFAIL:
				logger.error("Error occurred while recovering the branch transaction service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), xaex);
				break;
			default:
				logger.error("Illegal state, the status of the trying branch transaction is unknown!");
			}
		} catch (RuntimeException rex) {
			logger.error("Illegal resources, the status of the trying branch transaction is unknown!");
		}

		return null;
	}

	public synchronized void timingRecover() {
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		List<Transaction> transactions = transactionRepository.getErrorTransactionList();
		int total = transactions == null ? 0 : transactions.size(), value = 0;
		for (int i = 0; transactions != null && i < transactions.size(); i++) {
			Transaction transaction = transactions.get(i);
			org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
			TransactionXid xid = transactionContext.getXid();
			try {
				this.recoverTransactionIfNecessary(transaction);
			} catch (CommitRequiredException ex) {
				logger.debug("{}| recover: branch={}, message= commit-required",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()));
				continue;
			} catch (RollbackRequiredException ex) {
				logger.debug("{}| recover: branch={}, message= rollback-required",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()));
				continue;
			} catch (SystemException ex) {
				logger.debug("{}| recover: branch={}, message= {}", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex.getMessage(), ex);
				continue;
			} catch (RuntimeException ex) {
				logger.debug("{}| recover: branch={}, message= {}", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex.getMessage(), ex);
				continue;
			}
		}
		logger.debug("transaction-recovery: total= {}, success= {}", total, value);
	}

	public void recoverTransactionIfNecessary(Transaction transaction)
			throws CommitRequiredException, RollbackRequiredException, SystemException {
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
		int recoveredTimes = transactionContext.getRecoveredTimes() > 10 ? 10 : transactionContext.getRecoveredTimes();
		long recoverMillis = transactionContext.getCreatedTime() + SECOND_MILLIS * 60L * (long) Math.pow(2, recoveredTimes);

		if (System.currentTimeMillis() > recoverMillis) {
			this.recoverTransaction(transaction);
		} // end-if (System.currentTimeMillis() > recoverMillis)

	}

	public void recoverTransaction(Transaction transaction)
			throws CommitRequiredException, RollbackRequiredException, SystemException {
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();

		if (transactionContext.isCoordinator()) {
			transaction.recover();
			this.recoverCoordinator(transaction);
		} else {
			transaction.recover();
			this.recoverParticipant(transaction);
		}

	}

	protected void recoverCoordinator(Transaction transaction)
			throws CommitRequiredException, RollbackRequiredException, SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		TransactionLock compensableLock = this.beanFactory.getCompensableLock();

		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid xid = transactionContext.getXid();

		boolean forgetRequired = false;
		boolean locked = false;
		try {
			compensableManager.associateThread(transaction);

			switch (transaction.getTransactionStatus()) {
			case Status.STATUS_ACTIVE:
			case Status.STATUS_MARKED_ROLLBACK:
			case Status.STATUS_PREPARING:
			case Status.STATUS_UNKNOWN: /* TODO */ {
				if (transactionContext.isPropagated() == false) {
					if ((locked = compensableLock.lockTransaction(xid, this.endpoint)) == false) {
						throw new SystemException(XAException.XAER_PROTO);
					}

					transaction.recoveryRollback();
					forgetRequired = true;
				}
				break;
			}
			case Status.STATUS_ROLLING_BACK: {
				if ((locked = compensableLock.lockTransaction(xid, this.endpoint)) == false) {
					throw new SystemException(XAException.XAER_PROTO);
				}

				transaction.recoveryRollback();
				forgetRequired = true;
				break;
			}
			case Status.STATUS_PREPARED:
			case Status.STATUS_COMMITTING: {
				if ((locked = compensableLock.lockTransaction(xid, this.endpoint)) == false) {
					throw new SystemException(XAException.XAER_PROTO);
				}

				transaction.recoveryCommit();
				forgetRequired = true;
				break;
			}
			case Status.STATUS_COMMITTED:
			case Status.STATUS_ROLLEDBACK:
				forgetRequired = true;
				break;
			default: // ignore
			}
		} finally {
			compensableManager.desociateThread();
			if (locked) {
				compensableLock.unlockTransaction(xid, this.endpoint);
			} // end-if (locked)
			if (forgetRequired) {
				transaction.forgetQuietly(); // forget transaction
			} // end-if (forgetRequired)
		}

	}

	protected void recoverParticipant(Transaction transaction)
			throws CommitRequiredException, RollbackRequiredException, SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		try {
			compensableManager.associateThread(transaction);

			switch (transaction.getTransactionStatus()) {
			case Status.STATUS_COMMITTED:
			case Status.STATUS_ROLLEDBACK:
				transaction.forgetQuietly();
				break;
			default: // ignore
			}
		} finally {
			compensableManager.desociateThread();
		}

	}

	public synchronized void branchRecover() {
		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();

		List<Transaction> transactions = new ArrayList<Transaction>();
		transactions.addAll(transactionRepository.getActiveTransactionList());

		Map<String, List<Transaction>> instanceMap = new HashMap<String, List<Transaction>>();
		for (int i = 0; transactions != null && i < transactions.size(); i++) {
			Transaction transaction = transactions.get(i);
			Object txContext = transaction.getTransactionContext();
			if (TransactionContext.class.isInstance(txContext) == false) {
				continue; // ignore
			}

			TransactionContext transactionContext = (TransactionContext) txContext;
			if (transactionContext.isCoordinator()) {
				continue; // ignore
			} else if (transactionContext.isCompensable() == false) {
				continue; // ignore
			}

			String propagatedBy = String.valueOf(transactionContext.getPropagatedBy());
			List<Transaction> transactionList = instanceMap.get(propagatedBy);
			if (transactionList == null) {
				transactionList = new ArrayList<Transaction>();
				instanceMap.put(propagatedBy, transactionList);
			}
			transactionList.add(transaction);
		}

		Map<String, List<Transaction>> closedMap = new HashMap<String, List<Transaction>>();
		for (Iterator<Map.Entry<String, List<Transaction>>> itr = instanceMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, List<Transaction>> entry = itr.next();
			String identifier = entry.getKey();
			List<Transaction> transactionList = entry.getValue();
			XAResourceDescriptor resource = resourceDeserializer.deserialize(identifier);
			Xid[] xidArray = null;
			try {
				xidArray = resource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
			} catch (XAException ex) {
				continue; // ignore
			}

			Set<String> globals = new HashSet<String>();
			for (int i = 0; xidArray != null && i < xidArray.length; i++) {
				Xid xid = xidArray[i];
				byte[] globalTransactionId = xid.getGlobalTransactionId();
				String global = ByteUtils.byteArrayToString(globalTransactionId);
				globals.add(global);
			}

			List<Transaction> errorTxList = new ArrayList<Transaction>();
			for (int i = 0; transactionList != null && i < transactionList.size(); i++) {
				Transaction transaction = transactionList.get(i);
				org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
				TransactionXid transactionXid = transactionContext.getXid();
				byte[] globalTransactionId = transactionXid.getGlobalTransactionId();
				String global = ByteUtils.byteArrayToString(globalTransactionId);
				if (globals.contains(global) == false) {
					errorTxList.add(transaction);
				}
			}

			closedMap.put(identifier, errorTxList);
		}

		for (Iterator<Map.Entry<String, List<Transaction>>> itr = closedMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, List<Transaction>> entry = itr.next();
			List<Transaction> transactionList = entry.getValue();
			for (int i = 0; transactionList != null && i < transactionList.size(); i++) {
				Transaction transaction = transactionList.get(i);
				org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
				TransactionXid xid = transactionContext.getXid();
				try {
					this.rollbackParticipant(transaction);
				} catch (SystemException ex) {
					logger.debug("{}| recover(rollback): branch={}, message= {}",
							ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
							ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex.getMessage(), ex);
					continue;
				} catch (RuntimeException ex) {
					logger.debug("{}| recover(rollback): branch={}, message= {}",
							ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
							ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex.getMessage(), ex);
					continue;
				}
			}
		}
	}

	protected void rollbackParticipant(Transaction transaction) throws SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		try {
			compensableManager.associateThread(transaction);
			((CompensableTransactionImpl) transaction).fireRollback();
		} finally {
			compensableManager.desociateThread();
		}
	}

	public boolean isInitialized() {
		return initialized;
	}

	public boolean isStatefully() {
		return statefully;
	}

	public void setStatefully(boolean statefully) {
		this.statefully = statefully;
	}

	public String getEndpoint() {
		return this.endpoint;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

	public CompensableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	private static class TransactionBranchKey {
		public Xid xid;
		public String resource;

		public int hashCode() {
			int hash = 3;
			hash += 7 * (this.xid == null ? 0 : this.xid.hashCode());
			hash += 11 * (this.resource == null ? 0 : this.resource.hashCode());
			return hash;
		}

		public boolean equals(Object obj) {
			if (obj == null) {
				return false;
			} else if (TransactionBranchKey.class.isInstance(obj) == false) {
				return false;
			}
			TransactionBranchKey that = (TransactionBranchKey) obj;
			boolean xidEquals = CommonUtils.equals(this.xid, that.xid);
			boolean resEquals = StringUtils.equals(this.resource, that.resource);
			return xidEquals && resEquals;
		}
	}

}
