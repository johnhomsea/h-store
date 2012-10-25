package edu.brown.hstore.estimators.markov;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.voltdb.CatalogContext;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;
import org.voltdb.utils.EstTime;
import org.voltdb.utils.Pair;

import edu.brown.graphs.GraphvizExport;
import edu.brown.hstore.Hstoreservice.Status;
import edu.brown.hstore.estimators.EstimatorState;
import edu.brown.hstore.estimators.TransactionEstimator;
import edu.brown.hstore.txns.AbstractTransaction;
import edu.brown.interfaces.DebugContext;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.markov.MarkovEdge;
import edu.brown.markov.MarkovGraph;
import edu.brown.markov.MarkovGraphTimes;
import edu.brown.markov.MarkovUtil;
import edu.brown.markov.MarkovVertex;
import edu.brown.markov.containers.MarkovGraphsContainer;
import edu.brown.pools.TypedObjectPool;
import edu.brown.pools.TypedPoolableObjectFactory;
import edu.brown.profilers.MarkovEstimatorProfiler;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.ParameterMangler;
import edu.brown.utils.PartitionEstimator;
import edu.brown.utils.PartitionSet;
import edu.brown.utils.StringUtil;
import edu.brown.workload.QueryTrace;
import edu.brown.workload.TransactionTrace;

/**
 * Markov Model-based Transaction Estimator
 * @author pavlo
 */
public class MarkovEstimator extends TransactionEstimator {
    private static final Logger LOG = Logger.getLogger(MarkovEstimator.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    private static boolean d = debug.get();
    private static boolean t = trace.get();
    
    // ----------------------------------------------------------------------------
    // STATIC DATA MEMBERS
    // ----------------------------------------------------------------------------
    
    /**
     * The amount of change in visitation of vertices we would tolerate before we need
     * to recompute the graph.
     * TODO (pavlo): Saurya says: Should this be in MarkovGraph?
     */
    private static final double RECOMPUTE_TOLERANCE = (double) 0.5;

    // ----------------------------------------------------------------------------
    // DATA MEMBERS
    // ----------------------------------------------------------------------------
    
    private final CatalogContext catalogContext;
    private final MarkovGraphsContainer markovs;
    private final MarkovGraphTimes markovTimes = new MarkovGraphTimes();

    private final TypedObjectPool<MarkovPathEstimator> pathEstimatorsPool;
    private final TypedObjectPool<MarkovEstimatorState> statesPool;
    
    /**
     * We can maintain a cache of the last successful MarkovPathEstimator per MarkovGraph
     */
    private final Map<MarkovGraph, List<MarkovVertex>> cached_paths = new HashMap<MarkovGraph, List<MarkovVertex>>();
    
    private transient boolean enable_recomputes = false;
    
    /**
     * If we're using the TransactionEstimator, then we need to convert all 
     * primitive array ProcParameters into object arrays...
     */
    private final Map<Procedure, ParameterMangler> manglers;
    
    private final MarkovEstimatorProfiler profiler;
    
    // ----------------------------------------------------------------------------
    // CONSTRUCTORS
    // ----------------------------------------------------------------------------

    /**
     * Constructor
     * @param p_estimator
     * @param mappings
     * @param markovs
     */
    public MarkovEstimator(CatalogContext catalogContext, PartitionEstimator p_estimator, MarkovGraphsContainer markovs) {
        super(p_estimator);
        this.catalogContext = catalogContext;
        this.markovs = markovs;
        if (this.markovs != null && this.markovs.getHasher() == null) 
            this.markovs.setHasher(this.hasher);
        
        // Create all of our parameter manglers
        this.manglers = new IdentityHashMap<Procedure, ParameterMangler>();
        for (Procedure catalog_proc : this.catalogContext.database.getProcedures()) {
            if (catalog_proc.getSystemproc()) continue;
            this.manglers.put(catalog_proc, new ParameterMangler(catalog_proc));
        } // FOR
        if (debug.get()) LOG.debug(String.format("Created ParameterManglers for %d procedures",
                                   this.manglers.size()));
        
        if (d) LOG.debug("Creating MarkovPathEstimator Object Pool");
        TypedPoolableObjectFactory<MarkovPathEstimator> m_factory = new MarkovPathEstimator.Factory(this.catalogContext, this.p_estimator);
        this.pathEstimatorsPool = new TypedObjectPool<MarkovPathEstimator>(m_factory, hstore_conf.site.pool_pathestimators_idle);
        
        if (d) LOG.debug("Creating MarkovEstimatorState Object Pool");
        TypedPoolableObjectFactory<MarkovEstimatorState> s_factory = new MarkovEstimatorState.Factory(this.catalogContext); 
        statesPool = new TypedObjectPool<MarkovEstimatorState>(s_factory, hstore_conf.site.pool_estimatorstates_idle);
        
        if (hstore_conf.site.markov_profiling) {
            this.profiler = new MarkovEstimatorProfiler();
        } else {
            this.profiler = null;
        }
    }

    // ----------------------------------------------------------------------------
    // DATA MEMBER METHODS
    // ----------------------------------------------------------------------------

    @Override
    public void updateLogging() {
        d = debug.get();
        t = trace.get();
    }
    
    public void enableGraphRecomputes() {
       this.enable_recomputes = true;
    }
    public MarkovGraphsContainer getMarkovGraphsContainer() {
        return (this.markovs);
    }
    public MarkovGraphTimes getMarkovGraphTimes() {
        return (this.markovTimes);
    }
    
    // ----------------------------------------------------------------------------
    // RUNTIME METHODS
    // ----------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Override
    public MarkovEstimatorState startTransactionImpl(Long txn_id, int base_partition, Procedure catalog_proc, Object[] args) {
        ParameterMangler mangler = this.manglers.get(catalog_proc);
        if (mangler != null) args = mangler.convert(args);
        
        assert (catalog_proc != null);
        long start_time = EstTime.currentTimeMillis();
        if (d) LOG.debug(String.format("%s - Starting transaction estimation [partition=%d]",
                         AbstractTransaction.formatTxnName(catalog_proc, txn_id), base_partition));

        // If we don't have a graph for this procedure, we should probably just return null
        // This will be the case for all sysprocs
        if (this.markovs == null) return (null);
        MarkovGraph markov = this.markovs.getFromParams(txn_id, base_partition, args, catalog_proc);
        if (markov == null) {
            if (d) LOG.debug(String.format("%s - No MarkovGraph is available for transaction",
                             AbstractTransaction.formatTxnName(catalog_proc, txn_id)));
            return (null);
        }
        
        if (t) LOG.trace(String.format("%s - Creating new MarkovEstimatorState",
                         AbstractTransaction.formatTxnName(catalog_proc, txn_id)));
        MarkovEstimatorState state = null;
        try {
            state = (MarkovEstimatorState)statesPool.borrowObject();
            assert(state.isInitialized() == false);
            state.init(txn_id, base_partition, markov, args, start_time);
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
        assert(state.isInitialized()) : 
            "Unexpectted uninitialized MarkovEstimatorState\n" + state;
        
        MarkovVertex start = markov.getStartVertex();
        assert(start != null) : "The start vertex is null. This should never happen!";
        MarkovEstimate initialEst = state.createNextEstimate(start, true);
        this.estimatePath(state, initialEst, catalog_proc, args);
        assert(initialEst.getMarkovPath().isEmpty() == false);
        if (d) {
            String txnName = AbstractTransaction.formatTxnName(catalog_proc, txn_id);
            LOG.debug(String.format("%s - Initial MarkovEstimate\n%s", txnName, initialEst));
            if (t) {
                List<MarkovVertex> path = initialEst.getMarkovPath();
                LOG.trace(String.format("%s - Estimated Path [length=%d]\n%s",
                          txnName, path.size(),
                          StringUtil.join("\n----------------------\n", path)));
            }
        }
        
        // Update EstimatorState.prefetch any time we transition to a MarkovVertex where the
        // underlying Statement catalog object was marked as prefetchable
        // Do we want to put this traversal above?
        for (MarkovVertex vertex : initialEst.getMarkovPath()) {
            Statement statement = (Statement) vertex.getCatalogItem();
            if (statement.getPrefetchable()) {
                state.addPrefetchableStatement(vertex.getCountedStatement());
            }
        } // FOR
        
        // We want to add the estimate to the state down here after we have initialized
        // everything. This prevents other threads from accessing it before we have
        // initialized it properly.
        state.addInitialEstimate(initialEst);
        
        return (state);
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public MarkovEstimate executeQueries(EstimatorState s, Statement catalog_stmts[], PartitionSet partitions[], boolean allow_cache_lookup) {
        MarkovEstimatorState state = (MarkovEstimatorState)s; 
        if (d) LOG.debug(String.format("Processing %d queries for txn #%d", catalog_stmts.length, state.getTransactionId()));
        int batch_size = catalog_stmts.length;
        
        // If we get here, then we should definitely have a MarkovGraph
        MarkovGraph markov = state.getMarkovGraph();
        assert(markov != null);
            
        MarkovVertex current = state.getCurrent();
        PartitionSet touchedPartitions = state.getTouchedPartitions();
        MarkovVertex next_v = null;
        MarkovEdge next_e = null;
        Statement last_stmt = null;
        int stmt_idxs[] = null;
        boolean attempt_cache_lookup = false;

        if (attempt_cache_lookup && batch_size >= hstore_conf.site.markov_batch_caching_min) {
            assert(current != null);
            if (d) LOG.debug("Attempting cache look-up for last statement in batch: " + Arrays.toString(catalog_stmts));
            attempt_cache_lookup = true;
            
            state.cache_last_partitions.clear();
            state.cache_past_partitions.clear();
            
            PartitionSet last_partitions;
            stmt_idxs = new int[batch_size];
            for (int i = 0; i < batch_size; i++) {
                last_stmt = catalog_stmts[i];
                last_partitions = partitions[batch_size - 1];
                stmt_idxs[i] = state.updateQueryInstanceCount(last_stmt);
                if (i+1 != batch_size) state.cache_past_partitions.addAll(last_partitions);
                else state.cache_last_partitions.addAll(last_partitions);
            } // FOR
            
            Pair<MarkovEdge, MarkovVertex> pair = markov.getCachedBatchEnd(current,
                                                                           last_stmt,
                                                                           stmt_idxs[batch_size-1],
                                                                           state.cache_last_partitions,
                                                                           state.cache_past_partitions);
            if (pair != null) {
                next_e = pair.getFirst();
                assert(next_e != null);
                next_v = pair.getSecond();
                assert(next_v != null);
                if (d) LOG.debug(String.format("Got cached batch end for %s: %s -> %s", markov, current, next_v));
                
                // Update the counters and other info for the next vertex and edge
                if (this.enable_recomputes) {
                    this.markovTimes.addInstanceTime(next_v,
                                                     state.getTransactionId(),
                                                     state.getExecutionTimeOffset());
                }
                
                // Update the state information
                state.setCurrent(next_v, next_e);
                touchedPartitions.addAll(state.cache_last_partitions);
                touchedPartitions.addAll(state.cache_past_partitions);
            }
        }
        
        // Roll through the Statements in this batch and move the current vertex
        // for the txn's State handle along the path in the MarkovGraph
        if (next_v == null) {
            for (int i = 0; i < batch_size; i++) {
                int idx = (attempt_cache_lookup ? stmt_idxs[i] : -1);
                this.consume(state, markov, catalog_stmts[i], partitions[i], idx);
                if (attempt_cache_lookup == false) touchedPartitions.addAll(partitions[i]);
            } // FOR
            
            // Update our cache if we tried and failed before
            if (attempt_cache_lookup) {
                if (d) LOG.debug(String.format("Updating cache batch end for %s: %s -> %s", markov, current, state.getCurrent()));
                markov.addCachedBatchEnd(current,
                                         CollectionUtil.last(state.actual_path_edges),
                                         state.getCurrent(),
                                         last_stmt,
                                         stmt_idxs[batch_size-1],
                                         state.cache_past_partitions,
                                         state.cache_last_partitions);
            }
        }

        // 2012-10-17: This is kind of funky because we have to populate the
        // probabilities for the MarkovEstimate here, whereas for the initial estimate 
        // we did it inside of the MarkovPathEstimator
        MarkovEstimate estimate = state.createNextEstimate(state.getCurrent(), false);
        assert(estimate != null);
        Procedure catalog_proc = markov.getProcedure();
        Object procArgs[] = state.getProcedureParameters();
        this.estimatePath(state, estimate, catalog_proc, procArgs);
        
        if (d) LOG.debug(String.format("Next MarkovEstimate for txn #%d\n%s", state.getTransactionId(), estimate.toString()));
        assert(estimate.isInitialized()) :
            String.format("Unexpected uninitialized MarkovEstimate for txn #%d\n%s", state.getTransactionId(), estimate);
        assert(estimate.isValid()) :
            String.format("Invalid MarkovEstimate for txn #%d\n%s", state.getTransactionId(), estimate);
        
        // Once the workload shifts we detect it and trigger this method. Recomputes
        // the graph with the data we collected with the current workload method.
        if (this.enable_recomputes && markov.shouldRecompute(this.txn_count.get(), RECOMPUTE_TOLERANCE)) {
            markov.calculateProbabilities();
        }
        
        // We want to add the estimate to the state down here after we have initialized
        // everything. This prevents other threads from accessing it before we have
        // initialized it properly.
        state.addEstimate(estimate);
        
        return (estimate);
    }

    @Override
    protected void completeTransaction(EstimatorState s, Status status) {
        MarkovEstimatorState state = (MarkovEstimatorState)s;

        // The transaction for the given txn_id is in limbo, so we just want to remove it
        if (status != Status.OK && status != Status.ABORT_USER) {
            if (state != null && status == Status.ABORT_MISPREDICT) 
                state.getMarkovGraph().incrementMispredictionCount();
            return;
        }

        Long txn_id = state.getTransactionId();
        long timestamp = EstTime.currentTimeMillis();
        if (d) LOG.debug(String.format("Cleaning up state info for txn #%d [status=%s]",
                         txn_id, status));
        
        // We need to update the counter information in our MarkovGraph so that we know
        // that the procedure may transition to the ABORT vertex from where ever it was before 
        MarkovGraph g = state.getMarkovGraph();
        MarkovVertex current = state.getCurrent();
        MarkovVertex next_v = g.getFinishVertex(status);
        assert(next_v != null) : "Missing " + status;
        
        // If no edge exists to the next vertex, then we need to create one
        MarkovEdge next_e = g.findEdge(current, next_v);
        if (next_e == null) {
            synchronized (next_v) {
                next_e = g.findEdge(current, next_v);
                if (next_e == null) {
                    next_e = g.addToEdge(current, next_v);
                }
            } // SYNCH
        }
        state.setCurrent(next_v, next_e); // For post-txn processing...

        // Update counters
        // We want to update the counters for the entire path right here so that
        // nobody gets incomplete numbers if they recompute probabilities
        for (MarkovVertex v : state.actual_path) v.incrementInstanceHits();
        for (MarkovEdge e : state.actual_path_edges) e.incrementInstanceHits();
        if (this.enable_recomputes) {
            this.markovTimes.addInstanceTime(next_v, txn_id, state.getExecutionTimeOffset(timestamp));
        }
        
        // Store this as the last accurate MarkovPathEstimator for this graph
        if (hstore_conf.site.markov_path_caching &&
            this.cached_paths.containsKey(state.getMarkovGraph()) == false &&
            state.getInitialEstimate().isValid()) {
            
            MarkovEstimate initialEst = s.getInitialEstimate(); 
            synchronized (this.cached_paths) {
                if (this.cached_paths.containsKey(state.getMarkovGraph()) == false) {
                    if (d) LOG.debug(String.format("Storing cached MarkovVertex path for %s used by txn #%d",
                                                   state.getMarkovGraph().toString(), txn_id));
                    this.cached_paths.put(state.getMarkovGraph(), initialEst.getMarkovPath());
                }
            } // SYNCH
        }
        return;
    }
    
    @Override
    public void destroyEstimatorState(EstimatorState s) {
        this.statesPool.returnObject((MarkovEstimatorState)s);
    }

    // ----------------------------------------------------------------------------
    // INTERNAL ESTIMATION METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Estimate the execution path of the txn based on its current vertex in the graph
     * The estimate will be stored in the given MarkovEstimate
     * @param state The txn's TransactionEstimator state
     * @param est The current TransactionEstimate for the txn
     * @param catalog_proc The Procedure being executed by the txn 
     * @param args Procedure arguments (mangled)
     */
    private void estimatePath(MarkovEstimatorState state, MarkovEstimate est, Procedure catalog_proc, Object args[]) {
        assert(state.isInitialized()) : state.hashCode();
        assert(est.isInitialized()) : state.hashCode();
        if (d) LOG.debug(String.format("%s - Estimating execution path (%s)",
                         AbstractTransaction.formatTxnName(catalog_proc, state.getTransactionId()),
                         (est.isInitialEstimate() ? "INITIAL" : "BATCH #" + est.getBatchId())));
        
        MarkovVertex currentVertex = est.getVertex();
        assert(currentVertex != null);
        if (this.enable_recomputes) {
            this.markovTimes.addInstanceTime(currentVertex, state.getTransactionId(), EstTime.currentTimeMillis());
        }
      
        // TODO: If the current vertex is in the initial estimate's list,
        // then we can just use the truncated list as the estimate, since we know
        // that the path will be the same. We don't need to recalculate everything
        MarkovGraph markov = state.getMarkovGraph();
        assert(markov != null) :
            String.format("Unexpected null MarkovGraph for %s [hashCode=%d]\n%s",
                          AbstractTransaction.formatTxnName(catalog_proc, state.getTransactionId()), state.hashCode(), state);
        boolean compute_path = true;
        if (hstore_conf.site.markov_fast_path && currentVertex.isStartVertex() == false) {
            List<MarkovVertex> initialPath = ((MarkovEstimate)state.getInitialEstimate()).getMarkovPath();
            if (initialPath.contains(currentVertex)) {
                if (d) LOG.debug(String.format("%s - Using fast path estimation for %s",
                                 AbstractTransaction.formatTxnName(catalog_proc, state.getTransactionId()), markov));
                if (this.profiler != null) this.profiler.time_fast_estimate.start();
                try {
                    MarkovPathEstimator.fastEstimation(est, initialPath, currentVertex);
                    compute_path = false;
                } finally {
                    if (this.profiler != null) this.profiler.time_fast_estimate.stop();
                }
            }
        }
        // We'll reuse the last MarkovPathEstimator (and it's path) if the graph has been accurate for
        // other previous transactions. This prevents us from having to recompute the path every single time,
        // especially for single-partition transactions where the clustered MarkovGraphs are accurate
        else if (hstore_conf.site.markov_path_caching) {
            if (d) LOG.debug(String.format("%s - Checking whether we have a cached path for %s",
                             AbstractTransaction.formatTxnName(catalog_proc, state.getTransactionId()), markov));
            List<MarkovVertex> cached = this.cached_paths.get(markov);
            if (cached == null) {
                if (d) LOG.debug(String.format("%s - No cached path available for %s",
                                 AbstractTransaction.formatTxnName(catalog_proc, state.getTransactionId()), markov));
            }
            else if (markov.getAccuracyRatio() < hstore_conf.site.markov_path_caching_threshold) {
                if (d) LOG.debug(String.format("%s - MarkovGraph %s accuracy is below caching threshold [%.02f < %.02f]",
                        AbstractTransaction.formatTxnName(catalog_proc, state.getTransactionId()),
                        markov, markov.getAccuracyRatio(), hstore_conf.site.markov_path_caching_threshold));
            }
            else {
                if (this.profiler != null) this.profiler.time_cached_estimate.start();
                try {
                    MarkovPathEstimator.fastEstimation(est, cached, currentVertex);
                    compute_path = false;
                } finally {
                    if (this.profiler != null) this.profiler.time_cached_estimate.stop();
                }
            }
        }
        
        // Use the MarkovPathEstimator to estimate a new path for this txn
        if (compute_path) {
            if (d) LOG.debug(String.format("%s - Need to compute new path in %s using MarkovPathEstimator",
                             AbstractTransaction.formatTxnName(catalog_proc, state.getTransactionId()), markov));
            MarkovPathEstimator pathEstimator = null;
            try {
                pathEstimator = (MarkovPathEstimator)this.pathEstimatorsPool.borrowObject();
                pathEstimator.init(state.getMarkovGraph(), est, state.getBasePartition(), args);
                pathEstimator.setForceTraversal(true);
                // pathEstimator.setCreateMissing(true);
            } catch (Throwable ex) {
                String txnName = AbstractTransaction.formatTxnName(catalog_proc, state.getTransactionId());
                String msg = "Failed to intitialize new MarkovPathEstimator for " + txnName; 
                LOG.error(msg, ex);
                throw new RuntimeException(msg, ex);
            }
            
            if (this.profiler != null) this.profiler.time_full_estimate.start();
            try {
                pathEstimator.traverse(est.getVertex());
            } catch (Throwable ex) {
                try {
                    GraphvizExport<MarkovVertex, MarkovEdge> gv = MarkovUtil.exportGraphviz(markov, true, markov.getPath(pathEstimator.getVisitPath()));
                    LOG.error("GRAPH #" + markov.getGraphId() + " DUMP: " + gv.writeToTempFile(catalog_proc));
                } catch (Exception ex2) {
                    throw new RuntimeException(ex2);
                }
                String msg = "Failed to estimate path for " + AbstractTransaction.formatTxnName(catalog_proc, state.getTransactionId());
                LOG.error(msg, ex);
                throw new RuntimeException(msg, ex);
            } finally {
                if (this.profiler != null) this.profiler.time_full_estimate.stop();
            }
            
            this.pathEstimatorsPool.returnObject(pathEstimator);
        }
    }
    
    /**
     * Figure out the next vertex that the txn will transition to for the give Statement catalog object
     * and the partitions that it will touch when it is executed. If no vertex exists, we will create
     * it and dynamically add it to our MarkovGraph
     * @param txn_id
     * @param state
     * @param catalog_stmt
     * @param partitions
     */
    private void consume(MarkovEstimatorState state,
                         MarkovGraph markov,
                         Statement catalog_stmt,
                         PartitionSet partitions,
                         int queryInstanceIndex) {
        if (this.profiler != null) this.profiler.time_consume.start();
        
        // Update the number of times that we have executed this query in the txn
        if (queryInstanceIndex < 0) queryInstanceIndex = state.updateQueryInstanceCount(catalog_stmt);
        assert(markov != null);
        
        // Examine all of the vertices that are adjacent to our current vertex
        // and see which vertex we are going to move to next
        PartitionSet touchedPartitions = state.getTouchedPartitions();
        MarkovVertex current = state.getCurrent();
        assert(current != null);
        MarkovVertex next_v = null;
        MarkovEdge next_e = null;
        
        // Synchronize on the single vertex so that it's more fine-grained than the entire graph
        synchronized (current) {
            Collection<MarkovEdge> edges = markov.getOutEdges(current); 
            if (t) LOG.trace("Examining " + edges.size() + " edges from " + current + " for Txn #" + state.getTransactionId());
            for (MarkovEdge e : edges) {
                MarkovVertex v = markov.getDest(e);
                if (v.isEqual(catalog_stmt, partitions, touchedPartitions, queryInstanceIndex)) {
                    if (t) LOG.trace("Found next vertex " + v + " for Txn #" + state.getTransactionId());
                    next_v = v;
                    next_e = e;
                    break;
                }
            } // FOR
        
            // If we fail to find the next vertex, that means we have to dynamically create a new 
            // one. The graph is self-managed, so we don't need to worry about whether 
            // we need to recompute probabilities.
            if (next_v == null) {
                next_v = new MarkovVertex(catalog_stmt,
                                          MarkovVertex.Type.QUERY,
                                          queryInstanceIndex,
                                          partitions,
                                          touchedPartitions);
                markov.addVertex(next_v);
                next_e = markov.addToEdge(current, next_v);
                if (t) LOG.trace(String.format("Created new edge from %s to new vertex %s for txn #%d", 
                                 state.getCurrent(), next_v, state.getTransactionId()));
                assert(state.getCurrent().getPartitions().size() <= touchedPartitions.size());
            }
        } // SYNCH

        // Update the counters and other info for the next vertex and edge
        if (this.enable_recomputes) {
            this.markovTimes.addInstanceTime(next_v, state.getTransactionId(), state.getExecutionTimeOffset());
        }
        
        // Update the state information
        state.setCurrent(next_v, next_e);
        if (t) LOG.trace("Updated State Information for Txn #" + state.getTransactionId() + ":\n" + state);
        if (this.profiler != null) this.profiler.time_consume.stop();
    }

    // ----------------------------------------------------------------------------
    // HELPER METHODS
    // ----------------------------------------------------------------------------
    
    public MarkovEstimatorState processTransactionTrace(TransactionTrace txn_trace) throws Exception {
        Long txn_id = txn_trace.getTransactionId();
        if (d) {
            LOG.debug("Processing TransactionTrace #" + txn_id);
            if (t) LOG.trace(txn_trace.debug(this.catalogContext.database));
        }
        MarkovEstimatorState s = (MarkovEstimatorState)this.startTransaction(txn_id,
                                               txn_trace.getCatalogItem(this.catalogContext.database),
                                               txn_trace.getParams());
        assert(s != null) : "Null EstimatorState for txn #" + txn_id;
        
        for (Entry<Integer, List<QueryTrace>> e : txn_trace.getBatches().entrySet()) {
            int batch_size = e.getValue().size();
            if (t) LOG.trace(String.format("Batch #%d: %d traces", e.getKey(), batch_size));
            
            // Generate the data structures we will need to give to the TransactionEstimator
            Statement catalog_stmts[] = new Statement[batch_size];
            PartitionSet partitions[] = new PartitionSet[batch_size];
            this.populateQueryBatch(e.getValue(), s.getBasePartition(), catalog_stmts, partitions);
        
            synchronized (s.getMarkovGraph()) {
                this.executeQueries(s, catalog_stmts, partitions, false);
            } // SYNCH
        } // FOR (batches)
        if (txn_trace.isAborted()) {
            this.abort(s, Status.ABORT_USER);
        } else {
            this.commit(s);
        }
        
        assert(s.getEstimateCount() == txn_trace.getBatchCount()) :
            String.format("EstimateCount[%d] != BatchCount[%d]",
                          s.getEstimateCount(), txn_trace.getBatchCount());
        assert(s.actual_path.size() == (txn_trace.getQueryCount() + 2)) :
            String.format("Path[%d] != QueryCount[%d]",
                          s.actual_path.size(), txn_trace.getQueryCount());
        return (s);
    }
    
    private boolean populateQueryBatch(List<QueryTrace> queries, int base_partition, Statement catalog_stmts[], PartitionSet partitions[]) throws Exception {
        int i = 0;
        boolean readOnly = true;
        for (QueryTrace query_trace : queries) {
            assert(query_trace != null);
            catalog_stmts[i] = query_trace.getCatalogItem(catalogContext.database);
            partitions[i] = new PartitionSet();
            this.p_estimator.getAllPartitions(partitions[i], query_trace, base_partition);
            assert(partitions[i].isEmpty() == false) : "No partitions for " + query_trace;
            readOnly = readOnly && catalog_stmts[i].getReadonly();
            i++;
        } // FOR
        return (readOnly);
    }
    
    // ----------------------------------------------------------------------------
    // DEBUG METHODS
    // ----------------------------------------------------------------------------
    
    public class Debug implements DebugContext {
    
        public TypedObjectPool<MarkovPathEstimator> getPathEstimatorsPool() {
            return (pathEstimatorsPool);
        }
        
        public MarkovEstimatorProfiler getProfiler() {
            return (profiler);
        }
    } // CLASS
    
    private MarkovEstimator.Debug cachedDebugContext;
    public MarkovEstimator.Debug getDebugContext() {
        if (cachedDebugContext == null) {
            cachedDebugContext = new MarkovEstimator.Debug();
        }
        return cachedDebugContext;
    }

}