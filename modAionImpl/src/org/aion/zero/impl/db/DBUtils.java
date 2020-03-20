package org.aion.zero.impl.db;

import java.util.List;
import java.util.Map;
import org.aion.base.AionTransaction;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.log.LogLevel;
import org.aion.mcf.blockchain.Block;
import org.aion.zero.impl.blockchain.BlockWrapper;
import org.aion.zero.impl.config.CfgDb;
import org.aion.base.AccountState;
import org.aion.util.types.ByteArrayWrapper;
import org.aion.zero.impl.core.ImportResult;
import org.aion.mcf.db.Repository;
import org.aion.mcf.db.RepositoryCache;
import org.aion.types.AionAddress;
import org.aion.zero.impl.blockchain.AionBlockchainImpl;
import org.aion.zero.impl.types.AionGenesis;
import org.aion.zero.impl.config.CfgAion;
import org.aion.zero.impl.types.AionBlockSummary;
import org.aion.zero.impl.types.AionTxInfo;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;

/**
 * Methods used by CLI calls for debugging the local blockchain data.
 *
 * @author Alexandra Roatis
 * @implNote This class started off with helper functions for data recovery at runtime. It evolved
 *     into a diverse set of CLI calls for displaying or manipulating the data. It would benefit
 *     from refactoring to separate the different use cases.
 */
public class DBUtils {

    public enum Status {
        SUCCESS,
        FAILURE,
        ILLEGAL_ARGUMENT
    }

    public static void printStateTrieDump(long blockNumber) {
        // ensure mining is disabled
        CfgAion cfg = CfgAion.inst();
        cfg.dbFromXML();
        cfg.getConsensus().setMining(false);

        AionLoggerFactory.initAll(Map.of(LogEnum.GEN, LogLevel.INFO));
        final Logger log = AionLoggerFactory.getLogger(LogEnum.GEN.name());

        // get the current blockchain
        AionRepositoryImpl repository = AionRepositoryImpl.inst();

        AionBlockStore store = repository.getBlockStore();

        Block block;

        if (blockNumber == -1L) {
            block = store.getBestBlock();
            if (block == null) {
                log.error("The requested block does not exist in the database.");
                return;
            }
            blockNumber = block.getNumber();
        } else {
            block = store.getChainBlockByNumber(blockNumber);
            if (block == null) {
                log.error("The requested block does not exist in the database.");
                return;
            }
        }

        byte[] stateRoot = block.getStateRoot();
        log.info(
                "\nBlock hash: "
                        + block.getShortHash()
                        + ", number: "
                        + blockNumber
                        + ", tx count: "
                        + block.getTransactionsList().size()
                        + "\n\n"
                        + repository.getWorldState().getTrieDump(stateRoot));

        repository.close();
    }

    public static void pruneOrRecoverState(String pruning_type) {
        // ensure mining is disabled
        CfgAion cfg = CfgAion.inst();
        cfg.fromXML();
        cfg.getConsensus().setMining(false);

        // setting pruning to the version requested
        CfgDb.PruneOption option = CfgDb.PruneOption.fromValue(pruning_type);
        cfg.getDb().setPrune(option.toString());

        AionLoggerFactory.initAll(Map.of(LogEnum.GEN, LogLevel.INFO));
        final Logger log = AionLoggerFactory.getLogger(LogEnum.GEN.name());

        log.info("Reorganizing the state storage to " + option + " mode ...");

        AionBlockchainImpl chain = new AionBlockchainImpl(cfg, null, false);
        AionRepositoryImpl repo = chain.getRepository();
        AionBlockStore store = repo.getBlockStore();

        // dropping old state database
        log.info("Deleting old data ...");
        repo.getStateDatabase().drop();
        if (pruning_type.equals("spread")) {
            repo.getStateArchiveDatabase().drop();
        }

        // recover genesis
        log.info("Rebuilding genesis block ...");
        AionGenesis genesis = cfg.getGenesis();
        repo.buildGenesis(genesis);

        // recover all blocks
        Block block = store.getBestBlock();
        log.info(
                "Rebuilding the main chain "
                        + block.getNumber()
                        + " blocks (may take a while) ...");

        long topBlockNumber = block.getNumber();
        long blockNumber = 1000;

        // recover in increments of 1k blocks
        while (blockNumber < topBlockNumber) {
            block = store.getChainBlockByNumber(blockNumber);
            chain.recoverWorldState(repo, block);
            log.info("Finished with blocks up to " + blockNumber + ".");
            blockNumber += 1000;
        }

        block = store.getBestBlock();
        chain.recoverWorldState(repo, block);

        repo.close();
        log.info("Reorganizing the state storage COMPLETE.");
    }

    /**
     * Alternative to performing a full sync when the database already contains the <b>blocks</b>
     * and <b>index</b> databases. It will rebuild the entire blockchain structure other than these
     * two databases verifying consensus properties. It only redoes imports of the main chain
     * blocks, i.e. does not perform the checks for side chains.
     *
     * <p>The minimum start height is 0, i.e. the genesis block. Specifying a height can be useful
     * in performing the operation is sessions.
     *
     * @param startHeight the height from which to start importing the blocks
     * @implNote The assumption is that the stored blocks are correct, but the code may interpret
     *     them differently.
     */
    public static void redoMainChainImport(long startHeight) {
        // ensure mining is disabled
        CfgAion cfg = CfgAion.inst();
        cfg.dbFromXML();
        cfg.getConsensus().setMining(false);

        AionLoggerFactory.initAll(Map.of(LogEnum.GEN, LogLevel.INFO));
        final Logger LOG = AionLoggerFactory.getLogger(LogEnum.GEN.name());

        if (startHeight < 0) {
            LOG.error("Negative values are not valid as starting height. Nothing to do.");
            return;
        }

        LOG.info("Importing stored blocks INITIATED...");

        AionBlockchainImpl chain = new AionBlockchainImpl(cfg, null, false);
        AionRepositoryImpl repo = chain.getRepository();
        AionBlockStore store = repo.getBlockStore();

        // determine the parameters of the rebuild
        Block block = store.getBestBlock();
        Block startBlock;
        long currentBlock;
        if (block != null && startHeight <= block.getNumber()) {
            LOG.info("Importing the main chain from block #"
                            + startHeight
                            + " to block #"
                            + block.getNumber()
                            + ". This may take a while.\n"
                            + "The time estimates are optimistic based on current progress.\n"
                            + "It is expected that later blocks take a longer time to import due to the increasing size of the database.");

            if (startHeight == 0L) {
                // dropping databases that can be inferred when starting from genesis
                List<String> keep = List.of("block", "index");
                repo.dropDatabasesExcept(keep);

                // recover genesis
                AionGenesis genesis = cfg.getGenesis();
                store.redoIndexWithoutSideChains(genesis); // clear the index entry
                repo.buildGenesis(genesis);
                LOG.info("Finished rebuilding genesis block.");
                startBlock = genesis;
                currentBlock = 1L;
                chain.setTotalDifficulty(genesis.getDifficultyBI());
            } else {
                startBlock = store.getChainBlockByNumber(startHeight - 1);
                currentBlock = startHeight;
                // initial TD = diff of parent of first block to import
                Block blockWithDifficulties = store.getBlockByHashWithInfo(startBlock.getHash());
                chain.setTotalDifficulty(blockWithDifficulties.getTotalDifficulty());
            }

            boolean fail = false;

            if (startBlock == null) {
                LOG.info("The main chain block at level {} is missing from the database. Cannot continue importing stored blocks.", currentBlock);
                fail = true;
            } else {
                chain.setBestBlock(startBlock);

                long topBlockNumber = block.getNumber();
                long stepSize = 10_000L;

                Pair<ImportResult, AionBlockSummary> result;
                final int THOUSAND_MS = 1000;

                long start = System.currentTimeMillis();

                // import in increments of 10k blocks
                while (currentBlock <= topBlockNumber) {
                    block = store.getChainBlockByNumber(currentBlock);
                    if (block == null) {
                        LOG.error("The main chain block at level {} is missing from the database. Cannot continue importing stored blocks.", currentBlock);
                        fail = true;
                        break;
                    }

                    try {
                        // clear the index entry and prune side-chain blocks
                        store.redoIndexWithoutSideChains(block);
                        long t1 = System.currentTimeMillis();
                        result = chain.tryToConnectAndFetchSummaryFromDbUtil(new BlockWrapper(block));
                        long t2 = System.currentTimeMillis();
                        LOG.info("<import-status: hash = " + block.getShortHash() + ", number = " + block.getNumber()
                                               + ", txs = " + block.getTransactionsList().size() + ", result = " + result.getLeft()
                                               + ", time elapsed = " + (t2 - t1) + " ms, td = " + chain.getTotalDifficulty() + ">");
                    } catch (Throwable t) {
                        // we want to see the exception and the block where it occurred
                        t.printStackTrace();
                        if (t.getMessage() != null
                                && t.getMessage().contains("Invalid Trie state, missing node ")) {
                            LOG.info(
                                    "The exception above is likely due to a pruned database and NOT a consensus problem.\n"
                                            + "Rebuild the full state by editing the config.xml file or running ./aion.sh --state FULL.\n");
                        }
                        result =
                                new Pair<>() {
                                    @Override
                                    public AionBlockSummary setValue(AionBlockSummary value) {
                                        return null;
                                    }

                                    @Override
                                    public ImportResult getLeft() {
                                        return ImportResult.INVALID_BLOCK;
                                    }

                                    @Override
                                    public AionBlockSummary getRight() {
                                        return null;
                                    }
                                };

                        fail = true;
                    }

                    if (!result.getLeft().isSuccessful()) {
                        LOG.error("Consensus break at block:\n" + block);
                        LOG.info("Import attempt returned result "
                                        + result.getLeft()
                                        + " with summary\n"
                                        + result.getRight());

                        if (repo.isValidRoot(store.getBestBlock().getStateRoot())) {
                            LOG.info("The repository state trie was:\n");
                            LOG.info(repo.getTrieDump());
                        }

                        fail = true;
                        break;
                    }

                    if (currentBlock % stepSize == 0) {
                        double time = System.currentTimeMillis() - start;

                        double timePerBlock = time / (currentBlock - startHeight + 1);
                        long remainingBlocks = topBlockNumber - currentBlock;
                        double estimate =
                                (timePerBlock * remainingBlocks) / 60_000 + 1; // in minutes
                        LOG.info("Finished with blocks up to "
                                        + currentBlock
                                        + " in "
                                        + String.format("%.0f", time)
                                        + " ms (under "
                                        + String.format("%.0f", time / 60_000 + 1)
                                        + " min).\n\tThe average time per block is < "
                                        + String.format("%.0f", timePerBlock + 1)
                                        + " ms.\n\tCompletion for remaining "
                                        + remainingBlocks
                                        + " blocks estimated to take "
                                        + String.format("%.0f", estimate)
                                        + " min.");
                    }

                    currentBlock++;
                }
                LOG.info("Import from " + startHeight + " to " + topBlockNumber + " completed in " + (System.currentTimeMillis() - start) + " ms time.");
            }

            if (fail) {
                LOG.info("Importing stored blocks FAILED.");
            } else {
                LOG.info("Importing stored blocks SUCCESSFUL.");
            }
        } else {
            if (block == null) {
                LOG.info("The best known block in null. The given database is likely empty. Nothing to do.");
            } else {
                LOG.info("The given height "
                                + startHeight
                                + " is above the best known block "
                                + block.getNumber()
                                + ". Nothing to do.");
            }
        }

        LOG.info("Closing databases...");
        repo.close();

        LOG.info("Importing stored blocks COMPLETE.");
    }

    /** @implNote Used by the CLI call. */
    public static Status queryTransaction(byte[] txHash) {
        // ensure mining is disabled
        CfgAion cfg = CfgAion.inst();
        cfg.dbFromXML();
        cfg.getConsensus().setMining(false);

        AionLoggerFactory.initAll(Map.of(LogEnum.GEN, LogLevel.INFO));
        final Logger log = AionLoggerFactory.getLogger(LogEnum.GEN.name());

        // get the current blockchain
        AionBlockchainImpl blockchain = new AionBlockchainImpl(cfg, null, false);

        try {
            Map<ByteArrayWrapper, AionTxInfo> txInfoList = blockchain.getTransactionStore().getTxInfo(txHash);

            if (txInfoList == null || txInfoList.isEmpty()) {
                log.error("Can not find the transaction with given hash.");
                return Status.FAILURE;
            }

            for (Map.Entry<ByteArrayWrapper, AionTxInfo> entry : txInfoList.entrySet()) {

                Block block = blockchain.getBlockByHash(entry.getKey().toBytes());
                if (block == null) {
                    log.error("Cannot find the block data for the block hash from the transaction info. The database might be corrupted. Please consider reimporting the database by running ./aion.sh -n <network> --redo-import");
                    return Status.FAILURE;
                }

                AionTransaction tx = block.getTransactionsList().get(entry.getValue().getIndex());

                if (tx == null) {
                    log.error("Cannot find the transaction data for the given hash. The database might be corrupted. Please consider reimporting the database by running ./aion.sh -n <network> --redo-import");
                    return Status.FAILURE;
                }

                log.info(tx.toString());
                log.info(entry.getValue().toString());
            }

            return Status.SUCCESS;
        } catch (Exception e) {
            log.error("Error encountered while attempting to retrieve the transaction data.", e);
            return Status.FAILURE;
        }
    }

    /** @implNote Used by the CLI call. */
    public static Status queryBlock(long nbBlock) {
        // ensure mining is disabled
        CfgAion cfg = CfgAion.inst();
        cfg.dbFromXML();
        cfg.getConsensus().setMining(false);

        // TODO: add this log inside methods of interest
        // AionLoggerFactory.initAll(Map.of(LogEnum.QBCLI, LogLevel.DEBUG));
        AionLoggerFactory.initAll(Map.of(LogEnum.GEN, LogLevel.INFO));
        final Logger log = AionLoggerFactory.getLogger(LogEnum.GEN.name());

        // get the current blockchain
        AionBlockchainImpl blockchain = new AionBlockchainImpl(cfg, null, false);

        try {
            List<Block> blocks = blockchain.getRepository().getBlockStore().getAllChainBlockByNumber(nbBlock);

            if (blocks == null || blocks.isEmpty()) {
                log.error("Cannot find the block with given block height.");
                return Status.FAILURE;
            }

            for (Block b : blocks) {
                log.info(b.toString());
            }

            // Now print the transaction state. Only for the mainchain.

            // TODO: the worldstate can not read the data after the stateroot has been setup, need
            // to fix the issue first then the tooling can print the states between the block.

            Block mainChainBlock = blockchain.getBlockByNumber(nbBlock);
            if (mainChainBlock == null) {
                log.error("Cannot find the main chain block with given block height.");
                return Status.FAILURE;
            }

            Block parentBlock = blockchain.getBlockByHash(mainChainBlock.getParentHash());
            if (parentBlock == null) {
                log.error("Cannot find the parent block with given block height.");
                return Status.FAILURE;
            }

            blockchain.setBestBlock(parentBlock);
            // TODO: log to QBCLI info that we want printed out
            Pair<AionBlockSummary, RepositoryCache> result =
                    blockchain.tryImportWithoutFlush(mainChainBlock);
            log.info(
                    "Import result: "
                            + (result == null
                                    ? ImportResult.INVALID_BLOCK
                                    : ImportResult.IMPORTED_BEST));
            if (result != null) {
                log.info("Block summary:\n" + result.getLeft() + "\n");
                log.info("RepoCacheDetails:\n" + result.getRight());
            }
            // TODO: alternative to logging is to use the TrieImpl.scanTreeDiffLoop

            return Status.SUCCESS;
        } catch (Exception e) {
            log.error("Error encountered while attempting to retrieve the block data.", e);
            return Status.FAILURE;
        }
    }

    /** @implNote Used by the CLI call. */
    // TODO: more parameters would be useful, e.g. get account X at block Y
    public static Status queryAccount(AionAddress address) {
        // ensure mining is disabled
        CfgAion cfg = CfgAion.inst();
        cfg.dbFromXML();
        cfg.getConsensus().setMining(false);

        AionLoggerFactory.initAll(Map.of(LogEnum.GEN, LogLevel.INFO));
        final Logger log = AionLoggerFactory.getLogger(LogEnum.GEN.name());

        // get the current blockchain
        AionBlockchainImpl blockchain = new AionBlockchainImpl(cfg, null, false);

        try {
            Block bestBlock = blockchain.getRepository().getBestBlock();

            Repository<AccountState> repository =
                    blockchain
                            .getRepository()
                            .getSnapshotTo((bestBlock).getStateRoot())
                            .startTracking();

            AccountState account = repository.getAccountState(address);
            log.info(account.toString());

            log.info(repository.getContractDetails(address).toString());

            return Status.SUCCESS;
        } catch (Exception e) {
            log.error("Error encountered while attempting to retrieve the account data.", e);
            return Status.FAILURE;
        }
    }
}
