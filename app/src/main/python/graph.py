from typing import List, Tuple, Set

class Entity:
    def __init__(self, uri, label):
        self.uri = uri
        self.label = label   

    def get_label(self):
        return self.label
    
    def set_label(self, label):
        self.label = label

class Vertex(Entity):
    def __init__(self, uri, label):
        super().__init__(uri, label)
        self.status = False
        self.outward_degree = 0
    def __eq__(self, other):
        return isinstance(other, Vertex) and self.uri == other.uri
    def __hash__(self):
        return hash(self.uri)


class Edge(Entity):
    def __init__(self, uri, v1: Vertex, v2: Vertex, label):
        super().__init__(uri, label)
        self.v1 = v1
        self.v2 = v2
    def __eq__(self, other):
        return (
            isinstance(other, Edge) and 
            self.uri == other.uri
        )

class Graph:
    def __init__(self):
        self.edges = []
        self.vertices = []

    def add_vertex(self, vertex):
        self.vertices.append(vertex)

    
    def remove_vertex(self, vertex):
        self.vertices.remove(vertex)
        self.edges = [edge for edge in self.edges if edge.v1 != vertex and edge.v2 != vertex]
    
    def add_edge(self, v1, v2, label=None):
        uri = f"{v1.uri}->{v2.uri}"
        edge = Edge(uri, v1, v2, label)
        v1.outward_degree += 1
        self.edges.append(edge)
        return edge
    
    def remove_edge(self, v1, v2):
        self.edges = [edge for edge in self.edges if not (edge.v1 == v1 and edge.v2 == v2)]
        v1.outward_degree -= 1

    def adjacent(self, v1, v2):
        return any(edge for edge in self.edges if edge.v1 == v1 and edge.v2 == v2)
    
    def neighbors(self, vertex):
        return {edge.v2 for edge in self.edges if edge.v1 == vertex}

    def lookup(self, uri):
        if '->' in uri:
            # Check for matching edge
            for edge in self.edges:
                if edge.uri == uri:
                    return edge
        else:
            # Check for matching vertex
            for vertex in self.vertices:
                if vertex.uri == uri:
                    return vertex
        # If not found
        return None
    
    def is_leaf_node(self, v: Vertex):
        return not any(edge for edge in self.edges if edge.v1 == v)

    def extract_lineage_set(self, v: Vertex):
        lineage_graph = Graph()
        visited = set()

        def dfs(current_vertex):
            if current_vertex in visited:
                return
            visited.add(current_vertex)

            # Add current vertex to lineage graph
            lineage_graph.add_vertex(current_vertex)

            # Get outward edges
            for edge in self.get_edges(current_vertex):
                next_vertex = edge.v2
                # lineage_graph.add_vertex(next_vertex)
                lineage_graph.add_edge(current_vertex, next_vertex, edge.label)
                dfs(next_vertex)

        dfs(v)
        return lineage_graph

    def get_edges(self, v: Vertex) -> List[Edge]:
        edge_list = []
        for edge in self.edges:
            if edge.uri.split("->")[0] == v.uri:
                edge_list.append(edge)

        return edge_list

    def print_graph(self):
        print("Graph:")
        for vertex in self.vertices:
            outgoing = [edge for edge in self.edges if edge.v1 == vertex]
            if outgoing:
                print(f"  {vertex.label} ({vertex.uri}) ->")
                for edge in outgoing:
                    print(f"    └── {edge.v2.label} ({edge.v2.uri}) [label: {edge.label}]")
            else:
                print(f"  {vertex.label} ({vertex.uri}) -> ∅")

    
    def edge_cut_partition(self, n: int) -> List[Tuple['Graph', Set[Vertex]]]:
        """
        Partition the graph into n fragments using edge-cut strategy.
        Each fragment FiD is defined as (ViD ∪ OiD, EiD, LiD), where:
        - (V1, ..., Vn) is a partition of V
        - OiD is the set of border nodes that are not in ViD but have incoming edges from vertices in ViD
        - FiD is the subgraph induced by ViD ∪ OiD
        
        Args:
            n: Number of fragments to create
            
        Returns:
            List of tuples (Graph, Set[Vertex]), where each tuple contains:
            - Graph object representing the fragment (ViD)
            - Set of border nodes (OiD) for this fragment
        """
        if n <= 0 or n > len(self.vertices):
            raise ValueError("Number of partitions must be positive and not exceed number of vertices")
            
        # Initialize fragments and border node sets
        fragments = [Graph() for _ in range(n)]
        border_sets = [set() for _ in range(n)]
        
        # Calculate partition size and remainder
        partition_size = len(self.vertices) // n
        remainder = len(self.vertices) % n
        
        # Distribute vertices to fragments
        start_idx = 0
        vertex_to_fragment = {}  # Map to track which fragment owns each vertex
        
        for i in range(n):
            # Calculate size of this partition
            current_size = partition_size + (1 if i < remainder else 0)
            
            # Get vertices for this partition
            partition_vertices = self.vertices[start_idx:start_idx + current_size]
            start_idx += current_size
            
            # Add vertices to fragment and track ownership
            for vertex in partition_vertices:
                fragments[i].add_vertex(vertex)
                vertex_to_fragment[vertex] = i
        
        # Process edges and identify border nodes
        for edge in self.edges:
            v1, v2 = edge.v1, edge.v2
            
            # Find which fragment contains v1
            source_fragment_idx = vertex_to_fragment.get(v1)
            
            if source_fragment_idx is not None:
                target_fragment_idx = vertex_to_fragment.get(v2)
                
                # If v2 is in a different fragment, it becomes a border node
                if target_fragment_idx != source_fragment_idx and v2 not in fragments[source_fragment_idx].vertices:
                    border_sets[source_fragment_idx].add(v2)
                
                # Add the edge to the source fragment
                fragments[source_fragment_idx].add_edge(v1, v2, edge.label)
        
        # Return fragments with their border node sets
        return list(zip(fragments, border_sets))
    
    def serialize_to_cytoscape(self) -> dict:
        """
        Serialize the graph into Cytoscape.js compatible JSON format.
        
        Args:
            graph: The graph object to serialize
            
        Returns:
            dict: A dictionary in Cytoscape.js format
        """
        # Initialize the data structure without elements wrapper
        cytoscape_data = {
            "nodes": [],
            "edges": []
        }
        
        # Add nodes
        for vertex in self.vertices:
            node_data = {
                "id": vertex.uri,
                "labels": [vertex.label]
            }
            cytoscape_data["nodes"].append(node_data)
        
        # Add edges with auto-incrementing IDs
        edge_id = 1
        for edge in self.edges:
            edge_data = {
                "id": f"e{edge_id}",
                "source": edge.v1.uri,
                "target": edge.v2.uri,
                "labels": [edge.label]
            }
            cytoscape_data["edges"].append(edge_data)
            edge_id += 1
        
        return cytoscape_data

    def save_cytoscape_json(self,output_file: str):
        """
        Save the graph as a Cytoscape.js compatible JSON file.
        
        Args:
            graph: The graph object to serialize
            output_file: Path to save the JSON file
        """
        import json
        import os
        
        # Delete existing file if it exists
        if os.path.exists(output_file):
            os.remove(output_file)
        
        cytoscape_data = self.serialize_to_cytoscape()
        
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(cytoscape_data, f, ensure_ascii=False, indent=2)
        
    # def export_graph_to_json(self):

    def get_newly_added_vertices_count(self, original_vertex_uris: set) -> int:
        """
        Compare the current graph with the original graph and return the count of newly added nodes.
        Args:
            original_vertex_uris (set): Set of URIs from the original graph
        Returns:
            int: Number of newly added nodes
        """
        current_uris = set(v.uri for v in self.vertices)
        new_uris = current_uris - original_vertex_uris
        return len(new_uris)
    
    
    