package com.taobao.metamorphosis.server.network;

import java.util.concurrent.ThreadPoolExecutor;

import javax.transaction.xa.XAException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.taobao.gecko.service.Connection;
import com.taobao.gecko.service.RequestProcessor;
import com.taobao.metamorphosis.network.BooleanCommand;
import com.taobao.metamorphosis.network.HttpStatus;
import com.taobao.metamorphosis.network.RemotingUtils;
import com.taobao.metamorphosis.network.TransactionCommand;
import com.taobao.metamorphosis.server.CommandProcessor;
import com.taobao.metamorphosis.transaction.TransactionId;


/**
 * �����������
 * 
 * @author boyan(boyan@taobao.com)
 * @date 2011-8-17
 * 
 */
public class TransactionProcessor implements RequestProcessor<TransactionCommand> {

    private final CommandProcessor processor;

    private final ThreadPoolExecutor executor;


    public TransactionProcessor(final CommandProcessor processor, final ThreadPoolExecutor executor) {
        super();
        this.processor = processor;
        this.executor = executor;
    }


    @Override
    public void handleRequest(final TransactionCommand request, final Connection conn) {

        final TransactionId xid = request.getTransactionInfo().getTransactionId();
        final SessionContext context = SessionContextHolder.getOrCreateSessionContext(conn, xid);

        if (log.isDebugEnabled()) {
            log.debug(request);
        }
        try {
            switch (request.getTransactionInfo().getType()) {
            case BEGIN:
                this.processor.beginTransaction(context, xid, request.getTransactionInfo().getTimeout());
                this.responseOK(request, conn);
                break;
            case END:
                // ignore;
                break;
            case PREPARE:
                final int rt = this.processor.prepareTransaction(context, xid);
                // prepare����践��
                RemotingUtils.response(conn,
                    new BooleanCommand(request.getOpaque(), HttpStatus.Success, String.valueOf(rt)));
                break;
            // �ύ��,forget��rollback��ʱ����ͬ�����ã������ҪӦ��
            case COMMIT_ONE_PHASE:
                this.processor.commitTransaction(context, xid, true);
                this.responseOK(request, conn);
                break;
            case COMMIT_TWO_PHASE:
                this.processor.commitTransaction(context, xid, false);
                this.responseOK(request, conn);
                break;
            case FORGET:
                this.processor.forgetTransaction(context, xid);
                this.responseOK(request, conn);
                break;
            case ROLLBACK:
                this.processor.rollbackTransaction(context, xid);
                this.responseOK(request, conn);
                break;
            case RECOVER:
                final TransactionId[] xids = this.processor.getPreparedTransactions(context);
                final StringBuilder sb = new StringBuilder();
                boolean wasFirst = true;
                for (final TransactionId id : xids) {
                    if (wasFirst) {
                        sb.append(id.getTransactionKey());
                        wasFirst = false;
                    }
                    else {
                        sb.append("\r\n").append(id.getTransactionKey());
                    }
                }
                RemotingUtils
                    .response(conn, new BooleanCommand(request.getOpaque(), HttpStatus.Success, sb.toString()));
                break;
            default:
                RemotingUtils.response(conn, new BooleanCommand(request.getOpaque(), HttpStatus.InternalServerError,
                    "Unknow transaction command type:" + request.getTransactionInfo().getType()));

            }
        }
        catch (final XAException e) {
            log.error("Processing transaction command failed", e);
            // xa�쳣���⴦�����ÿͻ��˿���ֱ���׳�
            this.responseXAE(request, conn, e);
        }
        catch (final Exception e) {
            log.error("Processing transaction command failed", e);
            if (e.getCause() instanceof XAException) {
                this.responseXAE(request, conn, (XAException) e.getCause());
            }
            else {
                this.responseError(request, conn, e);
            }
        }
    }


    private void responseError(final TransactionCommand request, final Connection conn, final Exception e) {
        RemotingUtils.response(conn,
            new BooleanCommand(request.getOpaque(), HttpStatus.InternalServerError, e.getMessage()));
    }


    private void responseXAE(final TransactionCommand request, final Connection conn, final XAException e) {
        RemotingUtils.response(conn, new BooleanCommand(request.getOpaque(), HttpStatus.InternalServerError,
            "XAException:code=" + e.errorCode + ",msg=" + e.getMessage()));
    }

    static final Log log = LogFactory.getLog(TransactionProcessor.class);


    private void responseOK(final TransactionCommand request, final Connection conn) {
        RemotingUtils.response(conn, new BooleanCommand(request.getOpaque(), HttpStatus.Success, null));
    }


    @Override
    public ThreadPoolExecutor getExecutor() {
        return this.executor;
    }

}