package com.altamiracorp.securegraph;

import com.altamiracorp.securegraph.id.IdGenerator;
import com.altamiracorp.securegraph.query.DefaultGraphQuery;
import com.altamiracorp.securegraph.query.GraphQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class GraphBase implements Graph {
    private static final Logger LOGGER = LoggerFactory.getLogger(GraphBase.class);
    private final GraphConfiguration configuration;
    private final IdGenerator idGenerator;

    protected GraphBase(GraphConfiguration configuration, IdGenerator idGenerator) {
        this.configuration = configuration;
        this.idGenerator = idGenerator;
    }

    @Override
    public Vertex addVertex(Visibility visibility, Property... properties) {
        return addVertex(getIdGenerator().nextId(), visibility, properties);
    }

    @Override
    public abstract Vertex addVertex(Object vertexId, Visibility visibility, Property... properties);

    @Override
    public Vertex getVertex(Object vertexId, Authorizations authorizations) throws SecureGraphException {
        LOGGER.warn("Performing scan of all vertices! Override getVertex.");
        for (Vertex vertex : getVertices(authorizations)) {
            if (vertex.getId().equals(vertexId)) {
                return vertex;
            }
        }
        return null;
    }

    @Override
    public abstract Iterable<Vertex> getVertices(Authorizations authorizations) throws SecureGraphException;

    @Override
    public abstract void removeVertex(Vertex vertex, Authorizations authorizations);

    @Override
    public void removeVertex(String vertexId, Authorizations authorizations) {
        removeVertex(getVertex(vertexId, authorizations), authorizations);
    }

    @Override
    public Edge addEdge(Vertex outVertex, Vertex inVertex, String label, Visibility visibility, Property... properties) {
        return addEdge(getIdGenerator().nextId(), outVertex, inVertex, label, visibility, properties);
    }

    @Override
    public abstract Edge addEdge(Object edgeId, Vertex outVertex, Vertex inVertex, String label, Visibility visibility, Property... properties);

    @Override
    public Edge getEdge(Object edgeId, Authorizations authorizations) {
        LOGGER.warn("Performing scan of all edges! Override getEdge.");
        for (Edge edge : getEdges(authorizations)) {
            if (edge.getId().equals(edgeId)) {
                return edge;
            }
        }
        return null;
    }

    @Override
    public abstract Iterable<Edge> getEdges(Authorizations authorizations);

    @Override
    public abstract void removeEdge(Edge edge, Authorizations authorizations);

    @Override
    public void removeEdge(String edgeId, Authorizations authorizations) {
        removeEdge(getEdge(edgeId, authorizations), authorizations);
    }

    @Override
    public GraphQuery query(Authorizations authorizations) {
        return new DefaultGraphQuery(this, authorizations);
    }

    public IdGenerator getIdGenerator() {
        return idGenerator;
    }

    public GraphConfiguration getConfiguration() {
        return configuration;
    }
}
