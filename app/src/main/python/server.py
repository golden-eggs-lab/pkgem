import socket
import struct
import datetime

import tenseal as ts
from tenseal import Context

from concurrent.futures import ThreadPoolExecutor, as_completed
from graph import Graph, Vertex, Entity, Edge
import pickle
from typing import Dict, List, Tuple, Optional
from embedding_helper import EmbeddingHelper
from tenseal import CKKSVector
from mock_predictor import MockLLMPredictor
# from predictor import LLMPredictor
from util import get_random_mask, sum_values, merge_subgraph, remove_duplicate_vertices_by_label_and_edge_label, get_graph

import time
import json

# def log_with_time(msg):
#     now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]
#     print(f"[{now}] {msg}")

def serialize_encryption_map(encrypted_map: Dict[str, CKKSVector]) -> Dict[str, bytes]:
    result: Dict[str, bytes] = {}
    for uri, encryption in encrypted_map.items():
        result[uri] = encryption.serialize()
    return result

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


def h_v(vec1: CKKSVector, vec2: CKKSVector, decryption_socket: socket):
    start = time.time()

    if (vec1, vec2) in hv_cache:
        end = time.time()
        total_time = end - start

        if "Vertex Similarity" in times_dict:
            times_dict["Vertex Similarity"].append(total_time)
        else:
            times_dict["Vertex Similarity"] = [total_time]

        return hv_cache[(vec1, vec2)]

    m_v = (vec1.dot(vec2) - sigma) * mask
    m_v_bytes = pickle.dumps(m_v.serialize())

    response_bytes = struct.pack("!I", len(m_v_bytes) ) + struct.pack("!I", 1) + m_v_bytes

    if "Vertex Similarity" in bytes_sent_dict:
        bytes_sent_dict["Vertex Similarity"].append(len(response_bytes))
    else:
        bytes_sent_dict["Vertex Similarity"] = [len(response_bytes)]

    decryption_socket.sendall(response_bytes)

    response = decryption_socket.recv(4)
    bytes_rec_list.append(4)

    if not response:
        return None

    response_bool = bool(struct.unpack("!I", response)[0])
    end = time.time()
    total_time = end - start

    if "Vertex Similarity" in times_dict:
        times_dict["Vertex Similarity"].append(total_time)
    else:
        times_dict["Vertex Similarity"] = [total_time]

    hv_cache[(vec1, vec2)] = response_bool

    return response_bool

def h_p(path1: CKKSVector, path1_len: CKKSVector, path2: CKKSVector, path2_len: CKKSVector, decryption_socket: socket) -> float:
    # m_p = ((path1.dot(path2) * (1.0/(path1_len + path2_len))) - delta) * mask
    start = time.time()
    m_p = (path1.dot(path2)) * (0.25 * (path1_len + path2_len))
    # randomize the result
    m_p = m_p * mask
    # print(f"MP: {m_p.decrypt()[0]}")
    # print(f'Dot: {path1.dot(path2).decrypt()[0]}')
    m_p_bytes = pickle.dumps(m_p.serialize())

    response_bytes = struct.pack("!I", len(m_p_bytes)) + struct.pack("!I", 3) + m_p_bytes

    if "Path Similarity" in bytes_sent_dict:
        bytes_sent_dict["Path Similarity"].append(len(response_bytes))
    else:
        bytes_sent_dict["Path Similarity"] = [len(response_bytes)]

    decryption_socket.sendall(response_bytes)

    response = decryption_socket.recv(8)
    bytes_rec_list.append(8)
    response = struct.unpack('d', response)[0]
    # print(f'Response: {response}')
    end = time.time()
    total_time = end - start

    if "Path Similarity" in times_dict:
        times_dict["Path Similarity"].append(total_time)
    else:
        times_dict["Path Similarity"] = [total_time]

    return response

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

def get_vertex_object(v_uri: str) -> Optional[Vertex]:
        for v in vertices:
            if v.uri == v_uri:
                return v
        return None

def h_r(vec1: CKKSVector, k) -> Tuple[List[List[Vertex]], List[List[Edge]]]:
    start = time.time()
    P = []
    scores = []
    edges = []
    vec1_uri = list(encrypt_map_server.keys())[list(encrypt_map_server.values()).index(vec1)]
    list_edges = user_profile.get_edges(get_vertex_object(vec1_uri))
    
    for edge in list_edges:
        p = [edge.v1, edge.v2]
        e = [edge]
        chosen_edge = edge
        print(f'h_r Chosen Edge: {chosen_edge.v1.label}')
        print(f'h_r Chosen Edge: {chosen_edge.v2.label}')
        print(f'h_r Chosen Edge: {chosen_edge.v2.outward_degree}')
        while True:
            if chosen_edge.v2.outward_degree == 0:
                break

            candidate_edges = user_profile.get_edges(chosen_edge.v2)
            prediction = predictor.predict(chosen_edge.label, [word.label for word in candidate_edges])
            predicted_labels = prediction.split()  # ["apple", "banana", "cherry"]

            # if prediction == predictor.eos_token:
            #     break

            for cand in candidate_edges:
                for predicted_label in predicted_labels:
                    if cand.label == predicted_label:
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

def para_match(vec1: CKKSVector, vec1_uri: str, vec2: CKKSVector, vec2_uri: str, delta, k, decryption_socket: socket) -> bool:
    start = time.time()
    if not h_v(vec1, vec2, decryption_socket):
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

    if vec1 not in ecache:
        paths, edges = h_r(vec1, k)
        server_paths: List[List[CKKSVector]] = [[encrypt_map_server[x.uri] for x in path] for path in paths]
        server_uris = [[x.uri for x in path] for path in paths]
        server_lengths = [ts.ckks_vector(context, [1/len(path)]) for path in paths]
        server_edges: List[CKKSVector] = [model.encrypt_path(context, model.encode_path(x)) for x in edges]

        for path in paths:
            uri = path[1].uri
            V_server.append((uri, encrypt_map_server[uri]))
        ecache[vec1_uri] = V_server

    if vec2 not in ecache:
        k_serialized = struct.pack("!I", k)
        h_r_client_map = {"Vector": vec2_uri, "K": k_serialized}
        h_r_client_bytes = pickle.dumps(h_r_client_map)
        response_bytes = struct.pack("!I", len(h_r_client_bytes)) + struct.pack("!I", 2) + h_r_client_bytes

        if "Top-K Paths" in bytes_sent_dict:
            bytes_sent_dict["Top-K Paths"].append(len(response_bytes))
        else:
            bytes_sent_dict["Top-K Paths"] = [len(response_bytes)]

        try:
            decryption_socket.sendall(response_bytes)
        except Exception as e:
            print(f"[para_match] Error sending request for {vec2_uri}: {e}")
            return False

        msg = receive_full_message(decryption_socket)
        if msg is None:
            print(f"[para_match] No response received from client for {vec2_uri}")
            return False

        try:
            paths_edges_uris_map = pickle.loads(msg)
            print(f"[para_match] Successfully unpickled response for {vec2_uri}")
        except Exception as e:
            print(f"[para_match] Error unpickling response for {vec2_uri}: {e}")
            return False

        client_paths = []
        client_edges = []
        client_lengths = []
        client_uris = paths_edges_uris_map["URIs"]
        for path in paths_edges_uris_map["Vectors"]:
            client_paths.append(ts.ckks_vector_from(context, path))

        for edge in paths_edges_uris_map["Edges"]:
            client_edges.append(ts.ckks_vector_from(context, edge))

        for length in paths_edges_uris_map["Length"]:
            client_lengths.append(ts.ckks_vector_from(context, length))

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
            if h_v(server_prime_vec, client_prime_vec, decryption_socket):
                l_u_prime.append((client_prime_uri, client_prime_vec))
                score = h_p(server_edges[s_index], server_lengths[s_index], client_edges[c_index], client_lengths[c_index], decryption_socket)
                scores.append(score)
        sorted_l_u_prime = [k for _, k in sorted(zip(scores, l_u_prime), reverse=True, key=lambda pair: pair[0])]
        scores = sorted(scores)
        if len(scores) > 0:
            max_score += scores[0]
        if len(sorted_l_u_prime) > 0:
            L[(server_prime_uri, server_prime_vec)] = sorted_l_u_prime

    if max_score < delta:
        cache[(vec1_uri, vec2_uri)] = [False, []]
        return False

    for server_prime_uri, server_prime_vec in V_server:
        for client_prime_uri, client_prime_vec in L[(server_prime_uri, server_prime_vec)]:
            if (server_prime_uri, client_prime_uri) in cache:
                match = cache[(server_prime_uri, client_prime_uri)][0]
            else:
                match = para_match(server_prime_vec, server_prime_uri, client_prime_vec, client_prime_uri, delta, k, decryption_socket)
            if match:
                index = 0
                for path in server_paths:
                    if path[1] == server_prime_vec:
                        break
                    index += 1
                server_path = server_edges[index]
                p1_length = server_lengths[index]

                index = 0
                for path in client_paths:
                    if path == client_prime_vec:
                        break
                    index += 1
                p2_length = client_lengths[index]
                client_path = client_edges[index]
                sum += h_p(server_path, p1_length, client_path, p2_length, decryption_socket)
                W.append((server_prime_uri, client_prime_uri))
                if sum > delta:
                    cache[(vec1_uri, vec2_uri)] = [True, W]
                    return True
                break

            index = 0
            for path in server_paths:
                if path[1] == server_prime_vec:
                    break
                index += 1
            server_path = server_edges[index]
            p1_length = server_lengths[index]

            index = 0
            for path in client_paths:
                if path == client_prime_vec:
                    break
                index += 1
            client_path = client_edges[index]
            p2_length = client_lengths[index]
            max_score -= h_p(server_path, p1_length, client_path, p2_length, decryption_socket)

            for client_prime_n_uri, client_prime_n_vec in L[(server_prime_uri, server_prime_vec)]:
                if client_prime_n_uri != client_prime_uri:
                    index = 0
                    for path in client_paths:
                        if path == client_prime_n_vec:
                            break
                        index += 1
                    client_path = client_edges[index]
                    p2_length = client_lengths[index]

                    max_score += h_p(server_path, p1_length, client_path, p2_length, decryption_socket)

            if max_score < delta:
                break

    cache[(vec1_uri, vec2_uri)] = [False, []]

    for server_p_uri, client_p_uri in cache:
        if (vec1_uri, vec2_uri) in cache[(server_p_uri, client_p_uri)][1]:
            del cache[(server_p_uri, client_p_uri)]
            para_match(encrypt_map_server[server_p_uri], server_p_uri, client_encrypt_map[client_p_uri], client_p_uri, delta, k, decryption_socket)

    end = time.time()
    total_time = end - start
    if "ParaMatch" in times_dict:
        times_dict["ParaMatch"].append(total_time)
    else:
        times_dict["ParaMatch"] = [total_time]
    return False

def main(dataset_path, java_context, decryption_host, port=65432, progress_callback=None):
    global cache, context, model,user_profile, times_dict, bytes_sent_dict, bytes_rec_list, encrypt_map_server, hv_cache, mask, ecache, client_encrypt_map, predictor, sigma, delta, vertices
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
    model.encode_embedding(vertices)
    end = time.time()
    times_dict["Compute Embeddings"] = end - start

    mask = get_random_mask(1, 2, False)
    sigma = 0.95
    delta = 0.2

    hv_cache = {}

    ecache = {}

    progress_count = 0

    client_encrypt_map = {}
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
            serialized_encrypt_map_client = pickle.loads(b"".join(data))
            if isinstance(serialized_encrypt_map_client, Dict):
                print('Received encryption')
            else:
                print('Data corrupted')
            context = ts.context_from(data=serialized_encrypt_map_client['Context'])
            vertex_embedding_client = None
            uri_client = None
            for uri, vec in serialized_encrypt_map_client['Vertices'].items():
                uri_client = uri
                vertex_embedding_client = ts.ckks_vector_from(context, vec)
                client_encrypt_map[uri_client] = vertex_embedding_client
            encryption_start_time = time.time()
            encrypt_map_server = model.encrypt_embeddings(context, normalize = True)
            encryption_end_time = time.time()
            times_dict["Encryption"] = encryption_end_time - encryption_start_time
            decryption_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            decryption_socket.connect((decryption_host, port + 10))
            PI = {}
            C = {}
            expected_count = len(encrypt_map_server)
            print(f'log: expected_count {len(encrypt_map_server)}')
            progress_count = 0
            for uri_server, vec_server in encrypt_map_server.items():
                # print(f'log: for uri_server {uri_server}')
                PI[uri_server] = []
                C[uri_server] = []
                cache = {}
                

                def check_client(uri_client, vec_client):
                # first h_v check
                    if not h_v(vec_server, vec_client, decryption_socket):
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


                for uri_client, vec_client in client_encrypt_map.items():
                    result = check_client(uri_client, vec_client)
                    if result is not None:
                        PI[uri_server].append(result)
                progress_count += 1
                current_progress = progress_count / expected_count
                if progress_callback:
                    progress_callback.onProgressUpdate(int(current_progress * 100), f"Server: Computing...")
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