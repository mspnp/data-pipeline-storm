// Copyright (c) Microsoft Corporation. All rights reserved. See License.txt in the project root for license information.

package com.contoso.app.trident;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import backtype.storm.topology.FailedException;
import backtype.storm.tuple.Values;
import storm.trident.operation.BaseAggregator;
import storm.trident.operation.TridentCollector;
import storm.trident.operation.TridentOperationContext;
import storm.trident.topology.TransactionAttempt;
import storm.trident.tuple.TridentTuple;

public class ByteAggregator extends BaseAggregator<BlockState> {
	private static final long serialVersionUID = 1L;
	private static final Logger logger = (Logger) LoggerFactory.getLogger(ByteAggregator.class);
	private static String PARTITION_TXID_KEY_FORMATTER = "partition_%05d_transactionid";
	private static String PARTITION_FIRSTBLOCK_KEY_FORMATTER = "partition_%05d_firstblock";
	private static String PARTITION_LASTBLOCK_KEY_FORMATTER = "partition_%05d_lastblock";

	private long txid;
	private int partitionIndex;
	private long msgCount;
	public static String partitionTxidKeyStr = null;
	public static String partitionFirstblockKeyStr = null;
	public static String partitionLastblockKeyStr = null;
	
	static {
		String _PARTITION_TXID_KEY_FORMATTER = ConfigProperties.getProperty("PARTITION_TXID_KEY_FORMATTER");
		if (_PARTITION_TXID_KEY_FORMATTER != null) {
			PARTITION_TXID_KEY_FORMATTER = _PARTITION_TXID_KEY_FORMATTER; 
		}
		String _PARTITION_FIRSTBLOCK_KEY_FORMATTER = ConfigProperties.getProperty("PARTITION_FIRSTBLOCK_KEY_FORMATTER");
		if (_PARTITION_FIRSTBLOCK_KEY_FORMATTER != null) {
			PARTITION_FIRSTBLOCK_KEY_FORMATTER = _PARTITION_FIRSTBLOCK_KEY_FORMATTER; 
		}
		String _PARTITION_LASTBLOCK_KEY_FORMATTER = ConfigProperties.getProperty("PARTITION_LASTBLOCK_KEY_FORMATTER");
		if (_PARTITION_LASTBLOCK_KEY_FORMATTER != null) {
			PARTITION_LASTBLOCK_KEY_FORMATTER = _PARTITION_LASTBLOCK_KEY_FORMATTER; 
		}
	}

	public ByteAggregator() {
		if (LogSetting.LOG_BATCH) {
			logger.info("Constructor");
		}
	}

	@Override
	public void prepare(@SuppressWarnings("rawtypes") Map conf, TridentOperationContext context) {
		if (LogSetting.LOG_BATCH) {
			logger.info("prepare Begin");
		}
		this.partitionIndex = context.getPartitionIndex();
		ByteAggregator.partitionTxidKeyStr = String.format(PARTITION_TXID_KEY_FORMATTER, partitionIndex);
		ByteAggregator.partitionFirstblockKeyStr = String.format(PARTITION_FIRSTBLOCK_KEY_FORMATTER, partitionIndex);
		ByteAggregator.partitionLastblockKeyStr = String.format(PARTITION_LASTBLOCK_KEY_FORMATTER, partitionIndex);
		BlockStateStore.clearState(ByteAggregator.partitionTxidKeyStr, ByteAggregator.partitionFirstblockKeyStr,ByteAggregator.partitionLastblockKeyStr);
		super.prepare(conf, context);
		if (LogSetting.LOG_BATCH) {
			logger.info("p" + this.partitionIndex + ": prepare End");
		}
	}

	public BlockState init(Object batchId, TridentCollector collector) {
		if (LogSetting.LOG_BATCH) {
			logger.info("p" + this.partitionIndex + ": init End");
		}
		if (batchId instanceof TransactionAttempt) {
			this.txid = ((TransactionAttempt) batchId).getTransactionId();
		} else {
			throw new FailedException("Error configuring ByteAggregator");
		}
		msgCount = 0;
		BlockState blockList = new BlockState(this.partitionIndex, this.txid);
		if (LogSetting.LOG_BATCH) {
			logger.info(blockList.partitionTxidLogStr + "init End");
		}
		return blockList;
	}

	public void aggregate(BlockState blockList, TridentTuple tuple, TridentCollector collector) {
		if (LogSetting.LOG_MESSAGE) {
			logger.info(blockList.partitionTxidLogStr + "aggregate Begin");
		}
		String tupleStr = tuple.getString(0);
		if (tupleStr != null && tupleStr.length() > 0) {
			if (LogSetting.LOG_MESSAGE) {
				logger.info(blockList.partitionTxidLogStr + "Message= " + tupleStr);
			}
			String msg = tupleStr + "\r\n";
			if (Block.isMessageSizeWithnLimit(msg)) {
				if (blockList.currentBlock.willMessageFitCurrentBlock(msg)) {
					blockList.currentBlock.addData(msg);
				} else {
					// since the new msg will not fit into the current block, we will upload the current block,
					// and then get the next block, and add the new msg to the next block
					blockList.currentBlock.upload();
					blockList.needPersist = true;
					if (LogSetting.LOG_MESSAGEROLLOVER) {
						logger.info(blockList.partitionTxidLogStr + "Roll over from : blobname = " + blockList.currentBlock.blobname + ", blockid = "
								+ blockList.currentBlock.blockid);
					}
					blockList.currentBlock = blockList.getNextBlock(blockList.currentBlock);
					if (LogSetting.LOG_MESSAGEROLLOVER) {
						logger.info(blockList.partitionTxidLogStr + "Roll over to:    blobname = " + blockList.currentBlock.blobname + ", blockid = "
								+ blockList.currentBlock.blockid);
					}
					blockList.currentBlock.addData(msg);
				}
				msgCount++;
			} else {
				// message size is not within the limit, skip the message and log it.
				logger.error(blockList.partitionTxidLogStr + "message skiped: message size exceeds the size limit, message= " + tupleStr);
			}
		}
		if (LogSetting.LOG_MESSAGE) {
			logger.info(blockList.partitionTxidLogStr + "aggregate End");
		}
	}

	public void complete(BlockState blockList, TridentCollector collector) {
		if (LogSetting.LOG_BATCH) {
			logger.info(blockList.partitionTxidLogStr + "complete Begin");
		}
		if (blockList.currentBlock.blockdata.length() > 0) {
			blockList.currentBlock.upload();
			blockList.needPersist = true;
		}
		if (blockList.needPersist) {
			blockList.persistState();
		}
		collector.emit(new Values(msgCount));
		if (LogSetting.LOG_BATCH) {
			logger.info(blockList.partitionTxidLogStr + "message count = " + msgCount);
			logger.info(blockList.partitionTxidLogStr + "complete End");
		}
	}
}
