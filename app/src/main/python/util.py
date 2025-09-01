import random
import json
from typing import Dict
from graph import Vertex, Graph
import textwrap
from collections import defaultdict

def remove_duplicate_vertices_by_label_and_edge_label(graph: Graph):
    # Group vertices by (label, incoming_edge_label)
    incoming_map = defaultdict(list)

    # First, build a reverse lookup of incoming edges to each vertex
    for edge in graph.edges:
        key = (edge.v2.label, edge.label)
        incoming_map[key].append(edge.v2)

    visited = set()
    for (label, edge_label), duplicates in incoming_map.items():
        # Filter duplicates (must be >1 to remove)
        unique = []
        seen_uris = set()
        for v in duplicates:
            if v.uri not in seen_uris:
                unique.append(v)
                seen_uris.add(v.uri)
        if len(unique) <= 1:
            continue

        canonical = unique[0]
        for dup in unique[1:]:
            if dup in visited or dup == canonical:
                continue
            # Redirect all edges pointing to the duplicate to the canonical vertex
            for edge in graph.edges:
                if edge.v2 == dup and edge.label == edge_label:
                    edge.v2 = canonical
                    edge.uri = f"{edge.v1.uri}->{canonical.uri}"
            # Remove the duplicate vertex
            graph.remove_vertex(dup)
            visited.add(dup)

def sum_values(data: dict) -> int:
    total = 0
    for value in data.values():
        if isinstance(value, int):
            total += value
        elif isinstance(value, list):
            total += sum(value)
    return total

def get_random_mask(lb, ub, inclusive=False):
    while True:
        mask = random.uniform(ub, lb)
        if inclusive:
            return mask
        else:
            if lb < mask < ub:
                return mask

def merge_subgraph(x: Graph, x1: Vertex, y: Graph, y1: Vertex) -> Dict[Vertex, Vertex]:
    """
    Merge into graph y (at node y1) the entire subgraph of x rooted at x1.
    Preserves the original cycle/topology without infinite recursion.

    Returns a mapping from each visited x-node to its corresponding y-node.
    """
    print(f"log: x1 edges {x.get_edges(x1)}")
    
    mapping: Dict[Vertex, Vertex] = {x1: y1}
    visited = set()

    def dfs(u: Vertex):
        visited.add(u)
        parent_y = mapping[u]

        # Process outgoing edges
        for edge in x.get_edges(u):
            child_x = edge.v2
            lbl = edge.label

            # 1) If we've already mapped child_x, reuse it
            if child_x in mapping:
                target_y = mapping[child_x]
            else:
                # 2) Otherwise, look for an existing y-node under parent_y
                target_y = None
                for y_edge in y.get_edges(parent_y):
                    if y_edge.v2.label == child_x.label and y_edge.label == lbl:
                        target_y = y_edge.v2
                        break
                # 3) If none found, clone child_x into y
                if target_y is None:
                    target_y = Vertex(child_x.uri, child_x.label)
                    y.add_vertex(target_y)
                mapping[child_x] = target_y

            # 4) Ensure the edge (parent_y -> target_y, lbl) exists in y
            if not any(e.v2 == target_y and e.label == lbl for e in y.get_edges(parent_y)):
                y.add_edge(parent_y, target_y, lbl)

            # 5) Recurse if we haven't yet visited child_x
            if child_x not in visited:
                dfs(child_x)

    dfs(x1)
    return mapping

def merge_graphs(g1: Graph, g2: Graph) -> Graph:
    merged_graph = Graph()
    label_to_vertex = {}
    seen_edges = set()

    # Always prefer g1's root
    chosen_root_label = g1.vertices[0].label if g1.vertices else None
    other_root_label = g2.vertices[0].label if g2.vertices else None

    def add_vertex_to_merged(v: Vertex):
        # Redirect root from g2 if it has a different label
        label = v.label
        if label == other_root_label and label != chosen_root_label:
            label = chosen_root_label  # unify label

        if label not in label_to_vertex:
            # Use g1's URI if it's the chosen root
            if label == chosen_root_label:
                # Find g1's root to use its URI
                for v1 in g1.vertices:
                    if v1.label == chosen_root_label:
                        new_vertex = Vertex(v1.uri, label)
                        break
                else:
                    new_vertex = Vertex(v.uri, label)
            else:
                new_vertex = Vertex(v.uri, label)

            merged_graph.add_vertex(new_vertex)
            label_to_vertex[label] = new_vertex
        return label_to_vertex[label]

    # Add vertices from both graphs
    for v in g1.vertices + g2.vertices:
        add_vertex_to_merged(v)

    # Add deduplicated edges
    for e in g1.edges + g2.edges:
        v1 = add_vertex_to_merged(e.v1)
        v2 = add_vertex_to_merged(e.v2)
        edge_key = (v1.label, v2.label, e.label)
        if edge_key not in seen_edges:
            merged_graph.add_edge(v1, v2, e.label)
            seen_edges.add(edge_key)

    return merged_graph

def append_subgraph_at_uri(g: Graph, g1: Graph, uri: str):
    # Lookup the vertex in g where we will attach g1
    target = g.lookup(uri)
    if not isinstance(target, Vertex):
        raise ValueError(f"Vertex with URI '{uri}' not found in g")

    uri_to_vertex = {v.uri: v for v in g.vertices}
    label_to_vertex = {v.label: v for v in g.vertices}
    existing_edges = {(e.v1.label, e.v2.label, e.label) for e in g.edges}

    visited = set()
    g1_root = g1.vertices[0]  # Assume root is first

    # if g1_root.label != target.label:
    #     raise ValueError("Expected root of g1 to have the same label as target vertex in g")

    def dfs(v1: Vertex, g_v1: Vertex):
        if v1.uri in visited:
            return
        visited.add(v1.uri)

        for edge in g1.get_edges(v1):
            child = edge.v2

            # Try to find an existing vertex in g with the same label
            if child.label in label_to_vertex:
                g_child = label_to_vertex[child.label]
            else:
                g_child = Vertex(child.uri, child.label)
                g.add_vertex(g_child)
                uri_to_vertex[g_child.uri] = g_child
                label_to_vertex[g_child.label] = g_child

            edge_key = (g_v1.label, g_child.label, edge.label)
            if edge_key not in existing_edges:
                g.add_edge(g_v1, g_child, edge.label)
                existing_edges.add(edge_key)

            dfs(child, g_child)

    dfs(g1_root, target)


def get_graph(dataset_path, graph_prefix="g1"):
    with open(dataset_path) as json_file:
        data = json.load(json_file)

    graph = Graph()

    for node in data["nodes"]:
        uri = node["id"]
        label = node["labels"][0]
        graph.add_vertex(Vertex(f"{graph_prefix}/{uri}", label))

    for edge in data["edges"]:
        src_uri = f"{graph_prefix}/" + edge['source']
        tgt_uri = f"{graph_prefix}/" + edge["target"]
        label = edge["labels"][0]
        src_vertex = graph.lookup(src_uri)
        tgt_vertex = graph.lookup(tgt_uri)
        graph.add_edge(src_vertex, tgt_vertex, label)

    return graph




# def h_r(vec1: CKKSVector, k, encrypt_map, user_profile) -> List[List[Vertex]]:
#     P = []
#     scores = []
#     vec1_uri = list(encrypt_map_server.keys())[list(encrypt_map_server.values()).index(vec1)]
#     list_edges = user_profile.get_edges(get_vertex_object(vec1_uri))
#     for edge in list_edges:
#         p = [edge.v1, edge.v2]
#         chosen_edge = edge
#         while True:
#             if chosen_edge.v2.outward_degree == 0:
#                 break
#
#             candidate_edges = user_profile.get_edges(chosen_edge.v2)
#             prediction = predictor.predict(chosen_edge.label, [word.label for word in candidate_edges])
#             if prediction == predictor.tokenizer.eos_token:
#                 break
#
#             for cand in candidate_edges:
#                 if cand.label == prediction:
#                     chosen_edge = cand
#
#             if chosen_edge.v2 in p:
#                 break
#
#             p.append(chosen_edge.v2)
#
#         P.append(p)
#         scores.append(r_p(p))
#
#     sorted_paths = [k for _, k in sorted(zip(scores, P), reverse=True, key=lambda pair: pair[0])]
#
#     return sorted_paths[:k]
#
# def r_p(path: List[Vertex]) -> float:
#     product = 1
#
#     for i in range(0, len(path) - 1):
#         product = product * (1.0 / path[i].outward_degree)
#
#     return product
#
# def get_vertex_object(v_uri: str, vertices: List[Vertex]) -> Vertex | None:
#     for v in vertices:
#         if v.uri == v_uri:
#             return v
#     return None

def write_table(
        measurements,
        output_file,
        include_headers=True
):
    """
    Write measurements to a file in a 3-column, uniform-width table.

    measurements: list of (bytes_sent, bytes_received, time) tuples
    output_file:   path to output text file
    include_headers: if True, write the wrapped headers; otherwise skip them
    """
    headers = ["Total Bytes Sent", "Total Bytes Received", "Total Time"]

    # Compute the widest data string in any column
    data_width = max(
        max(len(str(sent)) for sent, _, _ in measurements),
        max(len(str(rec)) for _, rec, _ in measurements),
        max(len(f"{t:.3f}") for *_, t in measurements),
    )
    # Ensure headers can wrap at spaces without cutting words
    max_header_word = max(len(word) for h in headers for word in h.split())
    col_width = max(data_width, max_header_word)

    # Prepare wrapped headers
    wrapped_headers = [textwrap.wrap(h, width=col_width) for h in headers]
    max_lines = max(len(lines) for lines in wrapped_headers)
    for lines in wrapped_headers:
        lines += [""] * (max_lines - len(lines))

    total_width = 3 * col_width + 2  # two spaces between columns

    with open(output_file, "a") as f:
        if include_headers:
            # Write header lines
            for row in range(max_lines):
                for col in range(3):
                    f.write(wrapped_headers[col][row].ljust(col_width))
                    if col < 2:
                        f.write(" ")
                f.write("\n")
            # Separator
            f.write("-" * total_width + "\n")

        # Write each measurement row
        for sent, rec, t in measurements:
            f.write(str(sent).ljust(col_width))
            f.write(" ")
            f.write(str(rec).ljust(col_width))
            f.write(" ")
            f.write(f"{t:.3f}".ljust(col_width))
            f.write("\n")
