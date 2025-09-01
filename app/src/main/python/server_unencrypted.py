import socket
import struct
import datetime

# from concurrent.futures import ThreadPoolExecutor, as_completed
from graph import Graph, Vertex, Entity, Edge
import pickle
from typing import Dict, List, Tuple, Optional
from embedding_helper import EmbeddingHelper
from mock_predictor import MockLLMPredictor
import numpy as np
# from predictor import LLMPredictor
from util import get_random_mask, sum_values, merge_subgraph, remove_duplicate_vertices_by_label_and_edge_label, get_graph

import time
import json

# def log_with_time(msg):
#     now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]
#     print(f"[{now}] {msg}")

# Find the matching key
def find_key_by_value(dictionary, target_array):
    for key, value in dictionary.items():
        if np.array_equal(value, target_array):
            return key
    return None  # or raise an exception if needed

# def serialize_encryption_map(encrypted_map: Dict[str, CKKSVector]) -> Dict[str, bytes]:
#     result: Dict[str, bytes] = {}
#     for uri, encryption in encrypted_map.items():
#         result[uri] = encryption.serialize()
#     return result

def get_client_sub_graph(client_uri: str, decryption_socket: socket) -> Graph:
    client_uri_bytes = client_uri.encode()
    response_bytes = struct.pack("!I", len(client_uri_bytes)) + struct.pack("!I", 4) + client_uri_bytes

    if "Get Client Sub Graph" in bytes_sent_dict:
        bytes_sent_dict["Get Client Sub Graph"].append(len(response_bytes))
    else:
        bytes_sent_dict["Get Client Sub Graph"] = [len(response_bytes)]

    decryption_socket.sendall(response_bytes)
    response = receive_full_message(decryption_socket)
    return pickle.loads(response)


def h_v(vec1: np.ndarray, vec2: np.ndarray):
    start = time.time()

    if (vec1.tobytes(), vec2.tobytes()) in hv_cache:
        end = time.time()
        total_time = end - start

        if "Vertex Similarity" in times_dict:
            times_dict["Vertex Similarity"].append(total_time)
        else:
            times_dict["Vertex Similarity"] = [total_time]

        return hv_cache[(vec1.tobytes(), vec2.tobytes())]

    m_v = vec1.dot(vec2) - sigma
    response_bool = m_v >= 0
    end = time.time()
    total_time = end - start

    if "Vertex Similarity" in times_dict:
        times_dict["Vertex Similarity"].append(total_time)
    else:
        times_dict["Vertex Similarity"] = [total_time]

    hv_cache[(vec1.tobytes(), vec2.tobytes())] = response_bool

    return response_bool

def h_p(path1: np.ndarray, path1_len: float, path2: np.ndarray, path2_len: int) -> float:
    # m_p = ((path1.dot(path2) * (1.0/(path1_len + path2_len))) - delta) * mask
    start = time.time()
    m_p = (path1.dot(path2)) / (path1_len + path2_len)

    return m_p

def r_p(path: List[Vertex]) -> float:
    product = 1

    for i in range(0, len(path) - 1):
        product = product * (1.0 / path[i].outward_degree)

    return product

def receive_full_message(conn):
    """Receives a message with a fixed-length header."""
    msg_len_data = conn.recv(4)  # Get the first 4 bytes (message length)

    if not msg_len_data:
        return None
    msg_len = struct.unpack("!I", msg_len_data)[0]  # Unpack message length

    total_bytes_rec = 4 + msg_len
    bytes_rec_list.append(total_bytes_rec)

    # Receive the full message
    msg = b""
    while len(msg) < msg_len:
        msg_packet = conn.recv(msg_len - len(msg))
        if not msg_packet:
            return None
        msg += msg_packet
    return msg


def para_match(vec1: np.ndarray, vec1_uri: str, vec2: np.ndarray, vec2_uri: str, delta, k, decryption_socket: socket) -> bool:
    start = time.time()
    print(f"Vec1: {vec1_uri}, Vec2: {vec2_uri}, match: {h_v(vec1, vec2)}")
    
    if not h_v(vec1, vec2):
        cache[(vec1_uri, vec2_uri)] = [False, []]
        return False

    vertex = user_profile.lookup(vec1_uri)
    if type(vertex) != Vertex:
        return False

    if vertex.outward_degree == 0:
        cache[(vec1_uri, vec2_uri)] = [True, []]
        return True

    cache[(vec1_uri, vec2_uri)] = [True, []]
    W = []
    sum = 0

    V_server = []
    V_client = []

    if vec1_uri not in ecache:
        paths, edges = h_r(vec1, k)
        # print(f'Edges: {[[x.label for x in edge] for edge in edges]}')
        # print(f'Paths: {[[x.label for x in path] for path in paths]}')
        server_paths: List[List[np.ndarray]] = [[embedding_map_server[x.uri] for x in path] for path in paths]
        server_uris = [[x.uri for x in path] for path in paths]
        server_lengths = [len(path) for path in paths]
        server_edges: List[np.ndarray] = [model.encode_path(x) for x in edges]

        for path in paths:
            uri = path[1].uri
            V_server.append((uri, embedding_map_server[uri]))
        ecache[vec1_uri] = V_server

    if vec2_uri not in ecache:
        k_serialized = struct.pack("!I", k)

        h_r_client_map = {"Vector": vec2_uri, "K": k_serialized}
        h_r_client_bytes = pickle.dumps(h_r_client_map)

        response_bytes = struct.pack("!I", len(h_r_client_bytes)) + struct.pack("!I", 2) + h_r_client_bytes

        if "Top-K Paths" in bytes_sent_dict:
            bytes_sent_dict["Top-K Paths"].append(len(response_bytes))
        else:
            bytes_sent_dict["Top-K Paths"] = [len(response_bytes)]

        decryption_socket.sendall(response_bytes)
        msg = receive_full_message(decryption_socket)

        paths_edges_uris_map = pickle.loads(msg)

        client_paths = []
        client_edges = []
        client_lengths = []
        client_uris = paths_edges_uris_map["URIs"]
        # print(f"CLIENT URIs: {client_uris}")
        for path in paths_edges_uris_map["Vectors"]:
            client_paths.append(path)

        for edge in paths_edges_uris_map["Edges"]:
            client_edges.append(edge)

        for length in paths_edges_uris_map["Length"]:
            client_lengths.append(length)

        for i in range(0, len(client_paths)):
            V_client.append((client_uris[i], client_paths[i]))

        ecache[vec2_uri] = V_client

    L = {}
    max_score = 0
    for s_index in range(len(V_server)):
        server_prime_uri, server_prime_vec = V_server[s_index]
        l_u_prime = []
        scores = []
        for c_index in range(len(V_client)):
            client_prime_uri, client_prime_vec = V_client[c_index]
            # print(f"Client URI: {client_prime_uri}, Server URI: {server_prime_uri}")
            if h_v(server_prime_vec, client_prime_vec):
                # print("HERE")
                l_u_prime.append((client_prime_uri, client_prime_vec))
                score = h_p(server_edges[s_index], server_lengths[s_index], client_edges[c_index], client_lengths[c_index])
                scores.append(score)
        sorted_l_u_prime = [k for _, k in sorted(zip(scores, l_u_prime), reverse=True, key=lambda pair: pair[0])]
        scores = sorted(scores)
        if len(scores) > 0:
            max_score += scores[0]
        print(f"L_U': {sorted_l_u_prime}, Scores: {scores}")
        if len(sorted_l_u_prime) > 0:
            L[(server_prime_uri, server_prime_vec.tobytes())] = sorted_l_u_prime
            # L.append(sorted_l_u_prime)
    # print(L)
    # print(f"SCORE: {max_score}")

    if max_score < delta:
        cache[(vec1_uri, vec2_uri)] = [False, []]
        return False

    for server_prime_uri, server_prime_vec in V_server:
        for client_prime_uri, client_prime_vec in L[(server_prime_uri, server_prime_vec.tobytes())]:
            if (server_prime_uri, client_prime_uri) in cache:
                match = cache[(server_prime_uri, client_prime_uri)][0]
            else:
                match = para_match(server_prime_vec, server_prime_uri, client_prime_vec, client_prime_uri, delta, k, decryption_socket)
            if match:
                index = 0
                # p1_length = 0
                for path in server_paths:
                    if np.array_equal(path[1], server_prime_vec):
                        break
                    index += 1
                server_path = server_edges[index]
                p1_length = server_lengths[index]

                index = 0
                # p2_length = 0
                for path in client_paths:
                    if np.array_equal(path, client_prime_vec):
                        break
                    index += 1
                p2_length = client_lengths[index]
                client_path = client_edges[index]
                sum += h_p(server_path, p1_length, client_path, p2_length)
                W.append((server_prime_uri, client_prime_uri))
                if sum > delta:
                    cache[(vec1_uri, vec2_uri)] = [True, W]
                    return True
                break

            index = 0
            # p1_length = 0
            for path in server_paths:
                if np.array_equal(path[1], server_prime_vec):
                    break
                index += 1
            server_path = server_edges[index]
            p1_length = server_lengths[index]

            index = 0
            # p2_length = 0
            for path in client_paths:
                if np.array_equal(path, client_prime_vec):
                    break
                index += 1
            client_path = client_edges[index]
            p2_length = client_lengths[index]
            max_score -= h_p(server_path, p1_length, client_path, p2_length)

            for client_prime_n_uri, client_prime_n_vec in L[(server_prime_uri, server_prime_vec.tobytes())]:
                if client_prime_n_uri != client_prime_uri:
                    index = 0
                    # p2_length = 0
                    for path in client_paths:
                        if np.array_equal(path, client_prime_n_vec):
                            break
                        index += 1
                    client_path = client_edges[index]
                    p2_length = client_lengths[index]

                    max_score += h_p(server_path, p1_length, client_path, p2_length)

            if max_score < delta:
                break

    cache[(vec1_uri, vec2_uri)] = [False, []]

    for server_p_uri, client_p_uri in cache:
        if (vec1_uri, vec2_uri) in cache[(server_p_uri, client_p_uri)][1]:
            del cache[(server_p_uri, client_p_uri)]
            para_match(embedding_map_server[server_p_uri], server_p_uri, client_embed_map[client_p_uri], client_p_uri, delta, k, decryption_socket)

    end = time.time()
    total_time = end - start
    if "ParaMatch" in times_dict:
        times_dict["ParaMatch"].append(total_time)
    else:
        times_dict["ParaMatch"] = [total_time]
    return False

def get_vertex_object(v_uri: str) -> Optional[Vertex]:
        for v in vertices:
            if v.uri == v_uri:
                return v
        return None

def h_r(vec1: np.ndarray, k) -> Tuple[List[List[Vertex]], List[List[Edge]]]:
    start = time.time()
    P = []
    scores = []
    edges = []
    vec1_uri = find_key_by_value(embedding_map_server, vec1)
    # vec1_uri = list(embedding_map_server.keys())[list(embedding_map_server.values()).index(vec1)]
    list_edges = user_profile.get_edges(get_vertex_object(vec1_uri))
    for edge in list_edges:
        p = [edge.v1, edge.v2]
        e = [edge]
        chosen_edge = edge
        while True:
            if chosen_edge.v2.outward_degree == 0:
                break

            candidate_edges = user_profile.get_edges(chosen_edge.v2)
            prediction = predictor.predict(chosen_edge.label, [word.label for word in candidate_edges])
            # if prediction == predictor.tokenizer.eos_token:
            #     break

            for cand in candidate_edges:
                if cand.label == prediction:
                    chosen_edge = cand

            if chosen_edge.v2 in p:
                break

            p.append(chosen_edge.v2)
            e.append(chosen_edge)

        edges.append(e)
        P.append(p)
        scores.append(r_p(p))

    sorted_paths = [k for _, k in sorted(zip(scores, P), reverse=True, key=lambda pair: pair[0])]
    sorted_edges = [k for _, k in sorted(zip(scores, edges), reverse=True, key=lambda pair: pair[0])]
    end = time.time()
    total_time = end - start
    if "Top-K Paths" in times_dict:
        times_dict["Top-K Paths"].append(total_time)
    else:
        times_dict["Top-K Paths"] = [total_time]

    return sorted_paths[:k], sorted_edges[:k]

def main(dataset_path, java_context, decryption_host, port=65432, progress_callback=None):
    global cache,user_profile, times_dict, bytes_sent_dict, bytes_rec_list, model, embedding_map_server, hv_cache, mask, ecache, client_embed_map, predictor, sigma, delta, vertices
    times_dict = {}
    bytes_sent_dict = {}
    bytes_rec_list = []
    hv_cache = {}
    cache = {}
    host = "0.0.0.0"
    user_profile = get_graph(dataset_path, "g1")
    original_vertex_uris = set(v.uri for v in user_profile.vertices)

    # ====== Initialization ======
    if progress_callback:
        progress_callback.onProgressUpdate(0, "Server: Initializing...")

    start = time.time()
    model = EmbeddingHelper(java_context)
    end = time.time()
    times_dict["Initialize Sentence Transformer"] = end-start

    start = time.time()
    # predictor = LLMPredictor(context)
    predictor = MockLLMPredictor(java_context)
    end = time.time()
    times_dict["Initialize LLM"] = end - start

    vertices = user_profile.vertices

    start = time.time()
    embedding_map_server = model.encode_embedding(vertices)
    end = time.time()
    times_dict["Compute Embeddings"] = end - start

    mask = get_random_mask(1, 2, False)
    sigma = 0.95
    delta = 0.2

    hv_cache = {}

    ecache = {}

    progress_count = 0

    client_embed_map = {}
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind((host, port))
        s.listen()
        conn, addr = s.accept()
        with conn:
            start_time = time.time()
            data = []
            total_bytes = 0
            while True:
                packet = conn.recv(4096)
                if not packet:
                    break
                data.append(packet)
                total_bytes += len(packet)
            bytes_rec_list.append(len(b"".join(data)))
            serialized_embedding_map_client = pickle.loads(b"".join(data))
            if isinstance(serialized_embedding_map_client, Dict):
                print('Received encryption')
            else:
                print('Data corrupted')
            vertex_embedding_client = None
            uri_client = None
            for uri, vec in serialized_embedding_map_client['Vertices'].items():
                uri_client = uri
                vertex_embedding_client = vec
                client_embed_map[uri_client] = vertex_embedding_client
            # encryption_start_time = time.time()
            # encrypt_map_server = model.encrypt_embeddings(context, normalize = True)
            # encryption_end_time = time.time()
            # times_dict["Encryption"] = encryption_end_time - encryption_start_time
            serialized_map_server = {}
            decryption_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            decryption_socket.connect((decryption_host, port + 10))
            PI = {}
            C = {}
            expected_count = len(embedding_map_server)
            print(f'log: expected_count {len(embedding_map_server)}')
            progress_count = 0
            for uri_server, vec_server in embedding_map_server.items():
                PI[uri_server] = []
                C[uri_server] = []
                cache = {}
                def check_client(uri_client, vec_client):
                # first h_v check
                    if not h_v(vec_server, vec_client):
                        return None

                    # cache hit?
                    if cache.get((uri_server, uri_client), (False,))[0]:
                        return uri_client

                    # do the expensive match
                    match = para_match(
                        vec_server,
                        uri_server,
                        vec_client,
                        uri_client,
                        delta,
                        3,
                        decryption_socket
                    )
                    cache[(uri_server, uri_client)] = (bool(match),)

                    return uri_client if match else None
                for uri_client, vec_client in client_embed_map.items():
                    result = check_client(uri_client, vec_client)
                    if result is not None:
                        PI[uri_server].append(result)
                progress_count += 1
                current_progress = progress_count / expected_count
                if progress_callback:
                    progress_callback.onProgressUpdate(int(current_progress * 100), f"Server: Computing...")
            # for uri_server, vec_server in embedding_map_server.items():
            #     # print(f'log: for uri_server {uri_server}')
            #     PI[uri_server] = []
            #     C[uri_server] = []
            #     cache = {}
            PI_ordered = dict(sorted(PI.items(), key = lambda item: user_profile.lookup(item[0]).outward_degree, reverse=True))
            v_para_match_end = time.time()
            times_dict["VParaMatch"] = v_para_match_end - start_time
            print(f"PI Ordered: {PI_ordered}")
            enrichment_start_time = time.time()
            for uri_server in PI_ordered:
                # print(f'log: before merge_subgraph for {uri_server}')
                graph_map = {}
                for client_uri in PI_ordered[uri_server]:
                    client_sub_graph = get_client_sub_graph(client_uri, decryption_socket)
                    client_vertex = client_sub_graph.lookup(client_uri)
                    server_vertex = user_profile.lookup(uri_server)
                    merge_subgraph(client_sub_graph, client_vertex, user_profile, server_vertex)
                # print(f'log: after merge_subgraph for {uri_server}')
            try:
                decryption_socket.close()
            except Exception as e:
                print(f"Error during decryption_socket close: {e}")

            # Remove duplicate vertices before saving
            remove_duplicate_vertices_by_label_and_edge_label(user_profile)

            user_profile.print_graph()
            
            # save the result to a file
            import os
            output_file = os.path.join(java_context.getFilesDir().getAbsolutePath(), "graph.json")
            user_profile.save_cytoscape_json(output_file)
            
            enrichment_end_time = time.time()
            end_time = time.time()
            total_time = end_time - start_time
            times_dict["Enrichment"] = enrichment_end_time - enrichment_start_time
            data = struct.pack("!I", 0)
            bytes_sent_dict["End"] = 4
            conn.sendall(data)
            total_bytes_sent = sum_values(bytes_sent_dict)
            total_bytes_received = sum(bytes_rec_list)
            enriched_node_count = user_profile.get_newly_added_vertices_count(original_vertex_uris)

            # Return the required values
            return {
                "total_time": total_time,
                "total_bytes_received": total_bytes_received,
                "enriched_node_count": enriched_node_count,
                "graph_path": output_file
            }