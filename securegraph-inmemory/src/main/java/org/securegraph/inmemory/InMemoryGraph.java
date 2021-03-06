package org.securegraph.inmemory;

import org.securegraph.*;
import org.securegraph.event.*;
import org.securegraph.id.IdGenerator;
import org.securegraph.mutation.AlterPropertyVisibility;
import org.securegraph.mutation.PropertyRemoveMutation;
import org.securegraph.mutation.SetPropertyMetadata;
import org.securegraph.search.IndexHint;
import org.securegraph.search.SearchIndex;
import org.securegraph.util.ConvertingIterable;
import org.securegraph.util.LookAheadIterable;

import java.util.*;

import static org.securegraph.util.IterableUtils.toList;
import static org.securegraph.util.Preconditions.checkNotNull;

public class InMemoryGraph extends GraphBaseWithSearchIndex {
    private static final InMemoryGraphConfiguration DEFAULT_CONFIGURATION = new InMemoryGraphConfiguration(new HashMap());
    private final Map<String, InMemoryVertex> vertices;
    private final Map<String, InMemoryEdge> edges;
    private final Map<String, Object> metadata = new HashMap<>();

    protected InMemoryGraph(InMemoryGraphConfiguration configuration, IdGenerator idGenerator, SearchIndex searchIndex) {
        this(configuration, idGenerator, searchIndex, new HashMap<String, InMemoryVertex>(), new HashMap<String, InMemoryEdge>());
    }

    protected InMemoryGraph(InMemoryGraphConfiguration configuration, IdGenerator idGenerator, SearchIndex searchIndex, Map<String, InMemoryVertex> vertices, Map<String, InMemoryEdge> edges) {
        super(configuration, idGenerator, searchIndex);
        this.vertices = vertices;
        this.edges = edges;
    }

    public static InMemoryGraph create() {
        return create(DEFAULT_CONFIGURATION);
    }

    public static InMemoryGraph create(InMemoryGraphConfiguration config) {
        IdGenerator idGenerator = config.createIdGenerator();
        SearchIndex searchIndex = config.createSearchIndex();
        return create(config, idGenerator, searchIndex);
    }

    public static InMemoryGraph create(InMemoryGraphConfiguration config, IdGenerator idGenerator, SearchIndex searchIndex) {
        InMemoryGraph graph = new InMemoryGraph(config, idGenerator, searchIndex);
        graph.setup();
        return graph;
    }

    public static InMemoryGraph create(Map config) {
        return create(new InMemoryGraphConfiguration(config));
    }

    @Override
    public VertexBuilder prepareVertex(String vertexId, Visibility visibility) {
        if (vertexId == null) {
            vertexId = getIdGenerator().nextId();
        }

        return new VertexBuilder(vertexId, visibility) {
            @Override
            public Vertex save(Authorizations authorizations) {
                InMemoryVertex newVertex = new InMemoryVertex(InMemoryGraph.this, getVertexId(), getVisibility(), getProperties(), getPropertyRemoves(), null, authorizations);

                // to more closely simulate how accumulo works. add a potentially sparse (in case of an update) vertex to the search index.
                if (getIndexHint() != IndexHint.DO_NOT_INDEX) {
                    getSearchIndex().addElement(InMemoryGraph.this, newVertex, authorizations);
                }

                InMemoryVertex existingVertex = (InMemoryVertex) getVertex(getVertexId(), authorizations);
                InMemoryVertex vertex = InMemoryVertex.updateOrCreate(InMemoryGraph.this, existingVertex, newVertex, authorizations);
                vertices.put(getVertexId(), vertex);

                if (hasEventListeners()) {
                    fireGraphEvent(new AddVertexEvent(InMemoryGraph.this, vertex));
                    for (Property property : getProperties()) {
                        fireGraphEvent(new AddPropertyEvent(InMemoryGraph.this, vertex, property));
                    }
                    for (PropertyRemoveMutation propertyRemoveMutation : getPropertyRemoves()) {
                        fireGraphEvent(new RemovePropertyEvent(InMemoryGraph.this, vertex, propertyRemoveMutation));
                    }
                }

                return vertex;
            }
        };
    }

    @Override
    public Iterable<Vertex> getVertices(EnumSet<FetchHint> fetchHints, final Authorizations authorizations) throws SecureGraphException {
        final boolean includeHidden = fetchHints.contains(FetchHint.INCLUDE_HIDDEN);

        return new LookAheadIterable<InMemoryVertex, Vertex>() {
            @Override
            protected boolean isIncluded(InMemoryVertex src, Vertex vertex) {
                if (!src.canRead(authorizations)) {
                    return false;
                }

                if (!includeHidden) {
                    if (src.isHidden(authorizations)) {
                        return false;
                    }
                }

                return true;
            }

            @Override
            protected Vertex convert(InMemoryVertex vertex) {
                return filteredVertex(vertex, includeHidden, authorizations);
            }

            @Override
            protected Iterator<InMemoryVertex> createIterator() {
                return vertices.values().iterator();
            }
        };
    }

    @Override
    public void removeVertex(Vertex vertex, Authorizations authorizations) {
        if (!((InMemoryVertex) vertex).canRead(authorizations)) {
            return;
        }

        List<Edge> edgesToRemove = toList(vertex.getEdges(Direction.BOTH, authorizations));
        for (Edge edgeToRemove : edgesToRemove) {
            removeEdge(edgeToRemove, authorizations);
        }

        this.vertices.remove(vertex.getId());
        getSearchIndex().removeElement(this, vertex, authorizations);

        if (hasEventListeners()) {
            fireGraphEvent(new RemoveVertexEvent(this, vertex));
        }
    }

    @Override
    public void markVertexHidden(Vertex vertex, Visibility visibility, Authorizations authorizations) {
        if (!((InMemoryVertex) vertex).canRead(authorizations)) {
            return;
        }

        List<Edge> edgesToMarkHidden = toList(vertex.getEdges(Direction.BOTH, authorizations));
        for (Edge edgeToRemove : edgesToMarkHidden) {
            markEdgeHidden(edgeToRemove, visibility, authorizations);
        }

        this.vertices.get(vertex.getId()).addHiddenVisibility(visibility);
        getSearchIndex().addElement(this, vertex, authorizations);

        if (hasEventListeners()) {
            fireGraphEvent(new MarkHiddenVertexEvent(this, vertex));
        }
    }

    @Override
    public void markVertexVisible(Vertex vertex, Visibility visibility, Authorizations authorizations) {
        if (!((InMemoryVertex) vertex).canRead(authorizations)) {
            return;
        }

        List<Edge> edgesToMarkVisible = toList(vertex.getEdges(Direction.BOTH, FetchHint.ALL_INCLUDING_HIDDEN, authorizations));
        for (Edge edgeToMarkVisible : edgesToMarkVisible) {
            markEdgeVisible(edgeToMarkVisible, visibility, authorizations);
        }

        this.vertices.get(vertex.getId()).removeHiddenVisibility(visibility);
        getSearchIndex().addElement(this, vertex, authorizations);

        if (hasEventListeners()) {
            fireGraphEvent(new MarkVisibleVertexEvent(this, vertex));
        }
    }

    public void markPropertyHidden(InMemoryElement element, Property property, Visibility visibility, Authorizations authorizations) {
        if (!element.canRead(authorizations)) {
            return;
        }

        if (element instanceof Vertex) {
            this.vertices.get(element.getId()).markPropertyHiddenInternal(property, visibility);
        } else if (element instanceof Edge) {
            this.edges.get(element.getId()).markPropertyHiddenInternal(property, visibility);
        }

        if (hasEventListeners()) {
            fireGraphEvent(new MarkHiddenPropertyEvent(this, element, property, visibility));
        }
    }

    public void markPropertyVisible(InMemoryElement element, Property property, Visibility visibility, Authorizations authorizations) {
        if (!element.canRead(authorizations)) {
            return;
        }

        if (element instanceof Vertex) {
            this.vertices.get(element.getId()).markPropertyVisibleInternal(property, visibility);
        } else if (element instanceof Edge) {
            this.edges.get(element.getId()).markPropertyVisibleInternal(property, visibility);
        }

        if (hasEventListeners()) {
            fireGraphEvent(new MarkVisiblePropertyEvent(this, element, property, visibility));
        }
    }

    @Override
    public EdgeBuilderByVertexId prepareEdge(String edgeId, String outVertexId, String inVertexId, String label, Visibility visibility) {
        if (edgeId == null) {
            edgeId = getIdGenerator().nextId();
        }

        return new EdgeBuilderByVertexId(edgeId, outVertexId, inVertexId, label, visibility) {
            @Override
            public Edge save(Authorizations authorizations) {
                return savePreparedEdge(this, getOutVertexId(), getInVertexId(), authorizations);
            }
        };
    }

    @Override
    public EdgeBuilder prepareEdge(String edgeId, Vertex outVertex, Vertex inVertex, String label, Visibility visibility) {
        if (edgeId == null) {
            edgeId = getIdGenerator().nextId();
        }

        return new EdgeBuilder(edgeId, outVertex, inVertex, label, visibility) {
            @Override
            public Edge save(Authorizations authorizations) {
                return savePreparedEdge(this, getOutVertex().getId(), getInVertex().getId(), authorizations);
            }
        };
    }

    private Edge savePreparedEdge(EdgeBuilderBase edgeBuilder, String outVertexId, String inVertexId, Authorizations authorizations) {
        Edge existingEdge = getEdge(edgeBuilder.getEdgeId(), authorizations);

        Iterable<Property> properties;
        if (existingEdge == null) {
            properties = edgeBuilder.getProperties();
        } else {
            Iterable<Property> existingProperties = existingEdge.getProperties();
            Iterable<Property> newProperties = edgeBuilder.getProperties();
            properties = new TreeSet<>(toList(existingProperties));
            for (Property p : newProperties) {
                ((TreeSet<Property>) properties).remove(p);
                ((TreeSet<Property>) properties).add(p);
            }
        }

        Iterable<Visibility> hiddenVisibilities = null;
        if (existingEdge instanceof InMemoryEdge) {
            hiddenVisibilities = ((InMemoryEdge) existingEdge).getHiddenVisibilities();
        }

        String edgeLabel = edgeBuilder.getNewEdgeLabel();
        if (edgeLabel == null) {
            edgeLabel = edgeBuilder.getLabel();
        }

        InMemoryEdge edge = new InMemoryEdge(
                InMemoryGraph.this,
                edgeBuilder.getEdgeId(),
                outVertexId,
                inVertexId,
                edgeLabel,
                edgeBuilder.getVisibility(),
                properties,
                edgeBuilder.getPropertyRemoves(),
                hiddenVisibilities,
                authorizations
        );
        edges.put(edgeBuilder.getEdgeId(), edge);

        if (edgeBuilder.getIndexHint() != IndexHint.DO_NOT_INDEX) {
            getSearchIndex().addElement(InMemoryGraph.this, edge, authorizations);
        }

        if (hasEventListeners()) {
            fireGraphEvent(new AddEdgeEvent(InMemoryGraph.this, edge));
            for (Property property : edgeBuilder.getProperties()) {
                fireGraphEvent(new AddPropertyEvent(InMemoryGraph.this, edge, property));
            }
            for (PropertyRemoveMutation propertyRemoveMutation : edgeBuilder.getPropertyRemoves()) {
                fireGraphEvent(new RemovePropertyEvent(InMemoryGraph.this, edge, propertyRemoveMutation));
            }
        }

        return edge;
    }

    @Override
    public Iterable<Edge> getEdges(EnumSet<FetchHint> fetchHints, final Authorizations authorizations) {
        final boolean includeHidden = fetchHints.contains(FetchHint.INCLUDE_HIDDEN);

        return new LookAheadIterable<InMemoryEdge, Edge>() {
            @Override
            protected boolean isIncluded(InMemoryEdge src, Edge edge) {
                if (!src.canRead(authorizations)) {
                    return false;
                }

                if (!includeHidden) {
                    if (src.isHidden(authorizations)) {
                        return false;
                    }
                }

                return true;
            }

            @Override
            protected Edge convert(InMemoryEdge edge) {
                return filteredEdge(edge, includeHidden, authorizations);
            }

            @Override
            protected Iterator<InMemoryEdge> createIterator() {
                return edges.values().iterator();
            }
        };
    }

    @Override
    public void removeEdge(Edge edge, Authorizations authorizations) {
        if (!((InMemoryEdge) edge).canRead(authorizations)) {
            return;
        }

        this.edges.remove(edge.getId());
        getSearchIndex().removeElement(this, edge, authorizations);

        if (hasEventListeners()) {
            fireGraphEvent(new RemoveEdgeEvent(this, edge));
        }
    }

    @Override
    public Iterable<GraphMetadataEntry> getMetadata() {
        return new ConvertingIterable<Map.Entry<String, Object>, GraphMetadataEntry>(this.metadata.entrySet()) {
            @Override
            protected GraphMetadataEntry convert(Map.Entry<String, Object> o) {
                return new GraphMetadataEntry(o.getKey(), o.getValue());
            }
        };
    }

    @Override
    public Object getMetadata(String key) {
        return this.metadata.get(key);
    }

    @Override
    public void setMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }

    @Override
    public void markEdgeHidden(Edge edge, Visibility visibility, Authorizations authorizations) {
        if (!((InMemoryEdge) edge).canRead(authorizations)) {
            return;
        }

        Vertex inVertex = getVertex(edge.getVertexId(Direction.IN), authorizations);
        checkNotNull(inVertex, "Could not find in vertex: " + edge.getVertexId(Direction.IN));
        Vertex outVertex = getVertex(edge.getVertexId(Direction.OUT), authorizations);
        checkNotNull(outVertex, "Could not find out vertex: " + edge.getVertexId(Direction.OUT));

        this.edges.get(edge.getId()).addHiddenVisibility(visibility);
        getSearchIndex().addElement(this, edge, authorizations);

        if (hasEventListeners()) {
            fireGraphEvent(new MarkHiddenEdgeEvent(this, edge));
        }
    }

    @Override
    public void markEdgeVisible(Edge edge, Visibility visibility, Authorizations authorizations) {
        if (!((InMemoryEdge) edge).canRead(authorizations)) {
            return;
        }

        Vertex inVertex = getVertex(edge.getVertexId(Direction.IN), FetchHint.ALL_INCLUDING_HIDDEN, authorizations);
        checkNotNull(inVertex, "Could not find in vertex: " + edge.getVertexId(Direction.IN));
        Vertex outVertex = getVertex(edge.getVertexId(Direction.OUT), FetchHint.ALL_INCLUDING_HIDDEN, authorizations);
        checkNotNull(outVertex, "Could not find out vertex: " + edge.getVertexId(Direction.OUT));

        this.edges.get(edge.getId()).removeHiddenVisibility(visibility);
        getSearchIndex().addElement(this, edge, authorizations);

        if (hasEventListeners()) {
            fireGraphEvent(new MarkVisibleEdgeEvent(this, edge));
        }
    }

    @Override
    public Authorizations createAuthorizations(String... auths) {
        return new InMemoryAuthorizations(auths);
    }

    public Iterable<Edge> getEdgesFromVertex(final String vertexId, EnumSet<FetchHint> fetchHints, final Authorizations authorizations) {
        final boolean includeHidden = fetchHints.contains(FetchHint.INCLUDE_HIDDEN);

        return new LookAheadIterable<InMemoryEdge, Edge>() {
            @Override
            protected boolean isIncluded(InMemoryEdge src, Edge edge) {
                String inVertexId = src.getVertexId(Direction.IN);
                checkNotNull(inVertexId, "inVertexId was null");
                String outVertexId = src.getVertexId(Direction.OUT);
                checkNotNull(outVertexId, "outVertexId was null");

                if (!inVertexId.equals(vertexId) && !outVertexId.equals(vertexId)) {
                    return false;
                }

                if (!src.canRead(authorizations)) {
                    return false;
                }

                if (!includeHidden) {
                    if (src.isHidden(authorizations)) {
                        return false;
                    }
                }

                return true;
            }

            @Override
            protected Edge convert(InMemoryEdge edge) {
                return filteredEdge(edge, includeHidden, authorizations);
            }

            @Override
            protected Iterator<InMemoryEdge> createIterator() {
                return edges.values().iterator();
            }
        };
    }

    private boolean canRead(Visibility visibility, Authorizations authorizations) {
        // this is just a shortcut so that we don't need to construct evaluators and visibility objects to check for an empty string.
        if (visibility.getVisibilityString().length() == 0) {
            return true;
        }

        return authorizations.canRead(visibility);
    }

    public void saveProperties(
            Element element,
            Iterable<Property> properties,
            Iterable<PropertyRemoveMutation> propertyRemoves,
            IndexHint indexHint,
            Authorizations authorizations
    ) {
        if (element instanceof Vertex) {
            InMemoryVertex vertex = vertices.get(element.getId());
            vertex.updatePropertiesInternal(properties, propertyRemoves);
        } else if (element instanceof Edge) {
            InMemoryEdge edge = edges.get(element.getId());
            edge.updatePropertiesInternal(properties, propertyRemoves);
        } else {
            throw new IllegalArgumentException("Unexpected element type: " + element.getClass().getName());
        }

        if (indexHint != IndexHint.DO_NOT_INDEX) {
            for (PropertyRemoveMutation propertyRemoveMutation : propertyRemoves) {
                getSearchIndex().removeProperty(
                        this,
                        element,
                        propertyRemoveMutation.getKey(),
                        propertyRemoveMutation.getName(),
                        propertyRemoveMutation.getVisibility(),
                        authorizations
                );
            }
            getSearchIndex().addElement(this, element, authorizations);
        }

        if (hasEventListeners()) {
            InMemoryElement inMemoryElement;
            if (element instanceof Vertex) {
                inMemoryElement = vertices.get(element.getId());
            } else {
                inMemoryElement = edges.get(element.getId());
            }
            for (Property property : properties) {
                fireGraphEvent(new AddPropertyEvent(InMemoryGraph.this, inMemoryElement, property));
            }
            for (PropertyRemoveMutation propertyRemoveMutation : propertyRemoves) {
                fireGraphEvent(new RemovePropertyEvent(InMemoryGraph.this, inMemoryElement, propertyRemoveMutation));
            }
        }
    }

    public void removeProperty(Element element, Property property, Authorizations authorizations) {
        if (element instanceof Vertex) {
            InMemoryVertex vertex = vertices.get(element.getId());
            vertex.removePropertyInternal(property.getKey(), property.getName());
        } else if (element instanceof Edge) {
            InMemoryEdge edge = edges.get(element.getId());
            edge.removePropertyInternal(property.getKey(), property.getName());
        } else {
            throw new IllegalArgumentException("Unexpected element type: " + element.getClass().getName());
        }
        getSearchIndex().removeProperty(this, element, property, authorizations);

        if (hasEventListeners()) {
            fireGraphEvent(new RemovePropertyEvent(this, element, property));
        }
    }

    private Edge filteredEdge(InMemoryEdge edge, boolean includeHidden, Authorizations authorizations) {
        String edgeId = edge.getId();
        String outVertexId = edge.getVertexId(Direction.OUT);
        String inVertexId = edge.getVertexId(Direction.IN);
        String label = edge.getLabel();
        Visibility visibility = edge.getVisibility();
        Iterable<Visibility> hiddenVisibilities = edge.getHiddenVisibilities();
        List<Property> properties = filterProperties(edge.getProperties(), includeHidden, authorizations);
        return new InMemoryEdge(this, edgeId, outVertexId, inVertexId, label, visibility, properties, edge.getPropertyRemoveMutations(), hiddenVisibilities, authorizations);
    }

    private Vertex filteredVertex(InMemoryVertex vertex, boolean includeHidden, Authorizations authorizations) {
        String vertexId = vertex.getId();
        Visibility visibility = vertex.getVisibility();
        Iterable<Visibility> hiddenVisibilities = vertex.getHiddenVisibilities();
        List<Property> properties = filterProperties(vertex.getProperties(), includeHidden, authorizations);
        return new InMemoryVertex(this, vertexId, visibility, properties, vertex.getPropertyRemoveMutations(), hiddenVisibilities, authorizations);
    }

    private List<Property> filterProperties(Iterable<Property> properties, boolean includeHidden, Authorizations authorizations) {
        List<Property> filteredProperties = new ArrayList<>();
        for (Property p : properties) {
            if (canRead(p.getVisibility(), authorizations) && (includeHidden || !p.isHidden(authorizations))) {
                filteredProperties.add(p);
            }
        }
        return filteredProperties;
    }

    @SuppressWarnings("unused")
    public Map<String, InMemoryVertex> getAllVertices() {
        return this.vertices;
    }

    @SuppressWarnings("unused")
    public Map<String, InMemoryEdge> getAllEdges() {
        return this.edges;
    }

    void alterEdgeVisibility(String edgeId, Visibility newEdgeVisibility) {
        this.edges.get(edgeId).setVisibilityInternal(newEdgeVisibility);
    }

    void alterVertexVisibility(String vertexId, Visibility newVertexVisibility) {
        this.vertices.get(vertexId).setVisibilityInternal(newVertexVisibility);
    }

    void alterEdgePropertyVisibilities(String edgeId, List<AlterPropertyVisibility> alterPropertyVisibilities, Authorizations authorizations) {
        alterElementPropertyVisibilities(this.edges.get(edgeId), alterPropertyVisibilities, authorizations);
    }

    void alterVertexPropertyVisibilities(String vertexId, List<AlterPropertyVisibility> alterPropertyVisibilities, Authorizations authorizations) {
        alterElementPropertyVisibilities(this.vertices.get(vertexId), alterPropertyVisibilities, authorizations);
    }

    void alterElementPropertyVisibilities(InMemoryElement element, List<AlterPropertyVisibility> alterPropertyVisibilities, Authorizations authorizations) {
        for (AlterPropertyVisibility apv : alterPropertyVisibilities) {
            Property property = element.getProperty(apv.getKey(), apv.getName(), apv.getExistingVisibility());
            if (property == null) {
                throw new SecureGraphException("Could not find property " + apv.getKey() + ":" + apv.getName());
            }
            Object value = property.getValue();
            Metadata metadata = property.getMetadata();

            element.removeProperty(apv.getKey(), apv.getName(), authorizations);
            element.addPropertyValue(apv.getKey(), apv.getName(), value, metadata, apv.getVisibility(), authorizations);
        }
    }

    public void alterEdgePropertyMetadata(String edgeId, List<SetPropertyMetadata> setPropertyMetadatas) {
        alterElementPropertyMetadata(this.edges.get(edgeId), setPropertyMetadatas);
    }

    public void alterVertexPropertyMetadata(String vertexId, List<SetPropertyMetadata> setPropertyMetadatas) {
        alterElementPropertyMetadata(this.vertices.get(vertexId), setPropertyMetadatas);
    }

    private void alterElementPropertyMetadata(Element element, List<SetPropertyMetadata> setPropertyMetadatas) {
        for (SetPropertyMetadata apm : setPropertyMetadatas) {
            Property property = element.getProperty(apm.getPropertyKey(), apm.getPropertyName(), apm.getPropertyVisibility());
            if (property == null) {
                throw new SecureGraphException("Could not find property " + apm.getPropertyKey() + ":" + apm.getPropertyName());
            }

            property.getMetadata().add(apm.getMetadataName(), apm.getNewValue(), apm.getMetadataVisibility());
        }
    }

    @Override
    public boolean isVisibilityValid(Visibility visibility, Authorizations authorizations) {
        return authorizations.canRead(visibility);
    }

    @Override
    public void clearData() {
        this.vertices.clear();
        this.edges.clear();
        getSearchIndex().clearData();
    }

    public void alterEdgeLabel(String edgeId, String newEdgeLabel) {
        InMemoryEdge edge = this.edges.get(edgeId);
        if (edge == null) {
            throw new SecureGraphException("Could not find edge " + edgeId);
        }
        edge.setLabel(newEdgeLabel);
    }
}
