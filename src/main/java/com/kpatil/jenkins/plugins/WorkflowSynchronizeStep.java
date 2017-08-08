package com.kpatil.jenkins.plugins;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;

import com.thoughtworks.xstream.annotations.XStreamOmitField;

import hudson.Extension;
import hudson.Util;
import hudson.model.ManagementLink;
import hudson.model.ModelObject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ListBoxModel;
import jenkins.InitReactorRunner;
import jenkins.model.Jenkins;

@SuppressWarnings("rawtypes")
public class WorkflowSynchronizeStep extends AbstractStepImpl {

	private static final Logger LOGGER = Logger.getLogger(WorkflowSynchronizeStep.class.getName());
	
	private final String key;
	private final long timeout;
	private final TimeUnit unit;

	@DataBoundConstructor
	public WorkflowSynchronizeStep(String key, long timeout, TimeUnit unit) {
		this.key = key;
		this.timeout = timeout;
		this.unit = unit;
	}

	public String getKey() {
		return key;
	}

	public long getTimeout() {
		return timeout;
	}

	public TimeUnit getUnit() {
		return unit;
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(getClass());
	}

	@Extension
	public static final class Synchronizations extends ManagementLink implements ModelObject {
		@Override
		public String getIconFileName() {
			return "gear2.png";
		}

		@Nonnull
		public String getUrl() {
			return "build-synchronizations";
		}

		@Override
		public String getUrlName() {
			return getUrl();
		}

		@Override
		public String getDisplayName() {
			return "Build Synchronization";
		}

		public Map<String, Run> getLocks() {
			final Map<String, Run> result = new HashMap<>();
			for (Entry<String, LockState> lock : ((DescriptorImpl) Jenkins.getInstance()
					.getDescriptorOrDie(WorkflowSynchronizeStep.class)).getLocks().entrySet()) {
				Run run = lock.getValue().getOwner();
				if (run != null) {
					result.put(lock.getKey(), run);
				}
			}
			return result;
		}
	}

	@Extension
	public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

		public DescriptorImpl() {
			super(Execution.class);
			Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(new Runnable() {

				@Override
				public void run() {
					try {
						final Map<String, LockState> locks = new HashMap<>(getLocks());
						for (Entry<String, LockState> lock : locks.entrySet()) {
							final String key = lock.getKey();
							final LockState state = lock.getValue();
							if (state != null) {
								if (state.owner != null) {
									final Run<?, ?> run = state.owner.get();
									if (run == null || !run.isBuilding()) {
										release(key, state, run);
										LOGGER.info("Released stuck lock : [Key:" + key + "][Owner:" + run + "]");
									}
								} else {
									release(key, state, null);
								}
							}
						}
					} catch (Exception e) {
						LOGGER.warning("Failed to check on stuck locks - " + e);
						e.printStackTrace();
					}
				}

				private void release(final String key, final LockState state, final Run<?, ?> run) {
					try {
						state.lock.tryLock(1, TimeUnit.SECONDS);
						try {
							if (run != null && state.owner.get() == run) {
								state.owner = null;
							}
						} finally {
							state.availability.signalAll();
							state.lock.unlock();
						}
					} catch (InterruptedException e) {
						LOGGER.warning("Failed to release lock [Key:" + key + "] - " + e);
						e.printStackTrace();
					}
				}

			}, 0, 1, TimeUnit.MINUTES);
		}

		@XStreamOmitField
		private ConcurrentHashMap<String, LockState> locks = new ConcurrentHashMap<>();

		@Override
		public String getFunctionName() {
			return "synchronize";
		}

		@Override
		public String getDisplayName() {
			return "Build Synchronizations";
		}

		@Override
		public boolean takesImplicitBlockArgument() {
			return true;
		}

		public ListBoxModel doFillUnitItems() {
			ListBoxModel model = new ListBoxModel();
			for (TimeUnit unit : TimeUnit.values()) {
				model.add(unit.name(), unit.name());
			}
			return model;
		}

		public Map<String, LockState> getLocks() {
			return Collections.unmodifiableMap(locks);
		}

		private LockState getLockState(String key) {
			locks.putIfAbsent(key, new LockState());
			return locks.get(key);
		}
	}

	public static final class Execution extends AbstractSynchronousNonBlockingStepExecution<Object> {

		private static final long serialVersionUID = 1L;

		@Inject
		private transient WorkflowSynchronizeStep step; // NOSONAR
		@StepContextParameter
		private transient Run run;
		@StepContextParameter
		private transient TaskListener listener;

		@Override
		protected Object run() throws Exception {
			final StepContext context = getContext();
			if (acquire()) {
				try {
					log(listener, "Locked - [Key:" + step.getKey() + "]");
					return context.newBodyInvoker().start().get();
				} finally {
					release();
				}

			} else {
				throw new LockException("Locking Failed - [Key:" + step.getKey() + "]");
			}
		}

		private boolean acquire() throws InterruptedException {
			final LockState lockState = step.getDescriptor().getLockState(step.getKey());
			lockState.lock.lockInterruptibly();
			try {
				if (lockState.setOwner(run)) {
					return true;
				}
				if (step.getTimeout() > 0L) {
					log(listener, "Awaiting - [Duration:"
							+ Util.getTimeSpanString(step.getUnit().toMillis(step.getTimeout())) + "]");
					long timeout = step.getUnit().toNanos(step.getTimeout());
					while (!lockState.setOwner(run)) {
						log(listener, "Unavailable - [Owner:" + lockState.getOwner() + "][Key:" + step.getKey() + "]");
						if (timeout <= 0L) { // NOSONAR
							return false;
						}
						timeout = lockState.availability.awaitNanos(timeout);
					}
					return true;
				} else {
					log(listener, "Awaiting - [Duration:Indefinite]");
					while (!lockState.setOwner(run)) {
						log(listener, "Unavailable - [Owner:" + lockState.getOwner() + "][Key:" + step.getKey() + "]");
						lockState.availability.await();
					}
					return true;
				}
			} finally {
				lockState.lock.unlock();
			}
		}

		private void release() throws InterruptedException {
			step.getDescriptor().getLockState(step.getKey()).clearOwner(run);
			log(listener, "Released - [Key:" + step.getKey() + "]");
		}

	}

	private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");

	private static void log(TaskListener listener, String message) {
		listener.getLogger().println("[synchronize] " + DATE_FORMAT.format(new Date()) + " " + message);
	}

	public static class LockState {
		private final ReentrantLock lock = new ReentrantLock();
		private final Condition availability = lock.newCondition();
		private WeakReference<Run> owner = null;

		public Run getOwner() {
			if (owner != null) {
				Run<?, ?> run = owner.get();
				if (run != null) {
					return run;
				}
			}
			return null;
		}

		private boolean setOwner(Run owner) throws InterruptedException {
			if (owner == null) {
				throw new IllegalArgumentException("owner cant be null");
			}
			lock.lockInterruptibly();
			try {
				if (getOwner() == null) {
					this.owner = new WeakReference<>(owner);
					availability.signalAll();
					return true;
				}
				return false;
			} finally {
				lock.unlock();
			}
		}

		private boolean clearOwner(Run currentOwner) throws InterruptedException {
			lock.lockInterruptibly();
			try {
				if (currentOwner.equals(getOwner())) {
					this.owner = null;
					availability.signalAll();
					return true;
				}
				return false;
			} finally {
				lock.unlock();
			}
		}

	}

	private static class LockException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public LockException(String message) {
			super(message, null, false, false);
		}
	}
}
