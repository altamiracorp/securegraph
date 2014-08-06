package org.securegraph.accumulo.mapreduce;

import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.mapreduce.AccumuloInputFormat;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.WholeRowIterator;
import org.apache.hadoop.mapreduce.*;
import org.securegraph.Authorizations;
import org.securegraph.Element;
import org.securegraph.GraphFactory;
import org.securegraph.accumulo.AccumuloAuthorizations;
import org.securegraph.accumulo.AccumuloGraph;
import org.securegraph.util.MapUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

public abstract class AccumuloElementInputFormatBase<TValue extends Element> extends InputFormat<String, TValue> {
    private final AccumuloInputFormat accumuloInputFormat;

    public AccumuloElementInputFormatBase() {
        accumuloInputFormat = new AccumuloInputFormat();
    }

    protected static void setInputInfo(Job job, String instanceName, String zooKeepers, String principal, AuthenticationToken token, String[] authorizations, String tableName) throws AccumuloSecurityException {
        AccumuloInputFormat.setInputTableName(job, tableName);
        AccumuloInputFormat.setConnectorInfo(job, principal, token);
        AccumuloInputFormat.setZooKeeperInstance(job, instanceName, zooKeepers);
        AccumuloInputFormat.addIterator(job, new IteratorSetting(10, WholeRowIterator.class));
        job.getConfiguration().setStrings(SecureGraphMRUtils.CONFIG_AUTHORIZATIONS, authorizations);
    }

    @Override
    public List<InputSplit> getSplits(JobContext jobContext) throws IOException, InterruptedException {
        return accumuloInputFormat.getSplits(jobContext);
    }

    @Override
    public RecordReader<String, TValue> createRecordReader(InputSplit inputSplit, TaskAttemptContext taskAttemptContext) throws IOException, InterruptedException {
        final RecordReader<Key, Value> reader = accumuloInputFormat.createRecordReader(inputSplit, taskAttemptContext);
        return new RecordReader<String, TValue>() {
            public AccumuloGraph graph;
            public Authorizations authorizations;

            @Override
            public void initialize(InputSplit inputSplit, TaskAttemptContext ctx) throws IOException, InterruptedException {
                reader.initialize(inputSplit, ctx);

                Map configurationMap = SecureGraphMRUtils.toMap(ctx.getConfiguration());
                this.graph = (AccumuloGraph) new GraphFactory().createGraph(MapUtils.getAllWithPrefix(configurationMap, "graph"));
                this.authorizations = new AccumuloAuthorizations(ctx.getConfiguration().getStrings(SecureGraphMRUtils.CONFIG_AUTHORIZATIONS));
            }

            @Override
            public boolean nextKeyValue() throws IOException, InterruptedException {
                return reader.nextKeyValue();
            }

            @Override
            public String getCurrentKey() throws IOException, InterruptedException {
                return reader.getCurrentKey().getRow().toString();
            }

            @Override
            public TValue getCurrentValue() throws IOException, InterruptedException {
                SortedMap<Key, Value> row = WholeRowIterator.decodeRow(reader.getCurrentKey(), reader.getCurrentValue());
                return createElementFromRow(graph, row, authorizations);
            }

            @Override
            public float getProgress() throws IOException, InterruptedException {
                return reader.getProgress();
            }

            @Override
            public void close() throws IOException {
                reader.close();
            }
        };
    }

    protected abstract TValue createElementFromRow(AccumuloGraph graph, SortedMap<Key, Value> row, Authorizations authorizations);
}