import socket
import struct
import time
import logging

from encryption import Encryption
from tenseal import CKKSVector
import tenseal as ts

import json

from graph import Vertex, Graph, Entity, Edge
import pickle
from embedding_helper import EmbeddingHelper
from typing import List, Tuple, Optional

# from graph_example_client import get_graph
from mock_predictor import MockLLMPredictor
# from predictor import LLMPredictor
from util import get_random_mask, merge_graphs, append_subgraph_at_uri, sum_values, remove_duplicate_vertices_by_label_and_edge_label, get_graph

# 配置日志
logging.basicConfig(level=logging.DEBUG, format='[%(levelname)s] %(asctime)s %(message)s')
logger = logging.getLogger("Client")

def decrypt_vector(encrypted_vector_bytes):
    """Decrypts an encrypted CKKS vector."""
    encrypted_vector = ts.ckks_vector_from(context, encrypted_vector_bytes)
    return encrypted_vector.decrypt()[0]

def receive_full_message(conn):
    """Receives a message with a fixed-length header."""
    msg_len_data = conn.recv(4)  # Get the first 4 bytes (message length)
    if not msg_len_data:
        logger.warning("No message length data received.")
        return (None, None)
    msg_len = struct.unpack("!I", msg_len_data)[0]  # Unpack message length

    msg_type_data = conn.recv(4)
    if not msg_type_data:
        logger.warning("No message type data received.")
        return (None, None)
    msg_type = struct.unpack("!I", msg_type_data)[0]

    # Receive the full message
    msg = b""
    while len(msg) < msg_len:
        msg_packet = conn.recv(msg_len - len(msg))
        if not msg_packet:
            logger.warning("Message packet incomplete.")
            return (None, None)
        msg += msg_packet

    total_bytes_rec = 8 + msg_len
    bytes_rec_list.append(total_bytes_rec)

    # logger.debug(f"Received message of type {msg_type} and length {msg_len}")
    return msg_type, msg

def request_handler(conn):
    while True:
        msg_type, msg = receive_full_message(conn)
        if not msg:
            break  # Connection closed

        if msg_type == 1: #Vertex Similarity
            vertex_similarity_start_time = time.time()
            encrypted_vector_bytes = pickle.loads(msg)
            decrypted_values = decrypt_vector(encrypted_vector_bytes)
            logger.info(f"Vertex Similarity Decrypted values: {decrypted_values}")

            if decrypted_values < 0 - epsilon:
                response = 0
            else:
                response = 1
            # Send back the decrypted values
            response_bytes = struct.pack("!I", response)
            if "Vertex Similarity" in bytes_sent_dict:
                bytes_sent_dict["Vertex Similarity"].append(len(response_bytes))
            else:
                bytes_sent_dict["Vertex Similarity"] = [len(response_bytes)]
            conn.sendall(response_bytes)
            vertex_similarity_end_time = time.time()
            vertex_similarity_total_time = vertex_similarity_end_time - vertex_similarity_start_time
            if "Vertex Similarity" in times_dict:
                times_dict["Vertex Similarity"].append(vertex_similarity_total_time)
            else:
                times_dict["Vertex Similarity"] = [vertex_similarity_total_time]
        elif msg_type == 2:
            try:
                encrypted_vector_map = pickle.loads(msg)
            except Exception as e:
                raise

            client_vec_uri = encrypted_vector_map["Vector"]
            k = struct.unpack("!I", encrypted_vector_map["K"])[0]
            
            try:
                client_vec = encrypt_map_client[client_vec_uri]
            except KeyError as e:
                raise

            try:
                paths, edges = h_r(client_vec, k)
            except Exception as e:
                print(f"[request_handler] Error in h_r: {e}")
                raise

            edges_vectors = [model.encode_path(edge) for edge in edges]

            paths_vectors_encrypted = [[encrypt_map_client[x.uri] for x in path] for path in paths]
            path_length_encrypted = [ts.ckks_vector(context, [1/len(path)]) for path in paths]
            edges_vectors_encrypted = [model.encrypt_path(context, edge_vec) for edge_vec in edges_vectors]

            uris = [path[1].uri for path in paths]

            paths_serialized = [path[1].serialize() for path in paths_vectors_encrypted]
            edges_serialized = [edge.serialize() for edge in edges_vectors_encrypted]
            path_length_serialized = [length.serialize() for length in path_length_encrypted]

            paths_uris_edges_map_serialized = pickle.dumps({"URIs": uris, "Vectors": paths_serialized, "Edges": edges_serialized, "Length": path_length_serialized})
            client_top_k_paths_bytes = struct.pack("!I", len(paths_uris_edges_map_serialized)) + paths_uris_edges_map_serialized
            if "Top-K Paths" in bytes_sent_dict:    
                bytes_sent_dict["Top-K Paths"].append(len(client_top_k_paths_bytes))
            else:
                bytes_sent_dict["Top-K Paths"] = [len(client_top_k_paths_bytes)]

            conn.sendall(client_top_k_paths_bytes)
        elif msg_type == 3: #Path Similarity
            path_similarity_start_time = time.time()
            encrypted_vector_bytes = pickle.loads(msg)
            decrypted_values = decrypt_vector(encrypted_vector_bytes)
            
            print(f"log: decrypted_values {decrypted_values}")

            if decrypted_values < 0 - epsilon:
                response = (-1 * abs(decrypted_values)) * mask
            else:
                response = abs(decrypted_values) * mask
            response_bytes = struct.pack('d', response)

            if "Path Similarity" in bytes_sent_dict:
                bytes_sent_dict["Path Similarity"].append(len(response_bytes))
            else:
                bytes_sent_dict["Path Similarity"] = [len(response_bytes)]

            conn.sendall(response_bytes)
            path_similarity_end_time = time.time()
            path_similarity_total_time = path_similarity_end_time - path_similarity_start_time

            if "Path Similarity" in times_dict:
                times_dict["Path Similarity"].append(path_similarity_total_time)
            else:
                times_dict["Path Similarity"] = [path_similarity_total_time]

        elif msg_type == 4:
            uri = msg.decode()
            sub_graph = user_profile.extract_lineage_set(user_profile.lookup(uri))
            sub_graph_bytes = pickle.dumps(sub_graph)
            response_bytes = struct.pack("!I", len(sub_graph_bytes)) + sub_graph_bytes

            if "Sub Graph" in bytes_sent_dict:
                bytes_sent_dict["Sub Graph"].append(len(response_bytes))
            else:
                bytes_sent_dict["Sub Graph"] = [len(response_bytes)]

            conn.sendall(response_bytes)
        elif msg_type == 5: #Enrichment
            enrichment_start_time = time.time()
            server_sub_graph_uri_map = pickle.loads(msg)
            uri = server_sub_graph_uri_map['URI']
            server_sub_graph = server_sub_graph_uri_map['Subgraph']
            client_sub_graph = user_profile.extract_lineage_set(user_profile.lookup(uri))

            merged_graph = merge_graphs(server_sub_graph, client_sub_graph)
            append_subgraph_at_uri(user_profile, merged_graph, uri)
            enrichment_end_time = time.time()
            enrichment_total_time = enrichment_end_time - enrichment_start_time

            if "Enrichment" in times_dict:
                times_dict["Enrichment"].append(enrichment_total_time)
            else:
                times_dict["Enrichment"] = [enrichment_total_time]

def start_decryption_server():
    DECRYPTION_HOST = "0.0.0.0"
    PORT = 65432
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.bind((DECRYPTION_HOST, PORT + 10))
    server_socket.listen(1)

    conn, addr = server_socket.accept()
    # logger.info(f"Connected to server: {addr}")

    try:
        request_handler(conn)
    except Exception as e:
        logger.error(f"Error in request_handler: {e}")
    finally:
        conn.close()
        logger.info("Decryption server connection closed.")
        server_socket.close()
        logger.info("Decryption server socket closed.")

def get_vertex_object(v_uri: str) -> Optional[Vertex]:
    for v in vertices:
        if v.uri == v_uri:
            return v
    return None

def r_p(path: List[Vertex]) -> float:
    product = 1

    for i in range(0, len(path) - 1):
        product = product * (1.0 / path[i].outward_degree)

    return product

def h_r(vec1: CKKSVector, k: int) -> Tuple[List[List[Vertex]], List[List[Edge]]]:
    start = time.time()
    P = []
    scores = []
    edges = []
    vec1_uri = list(encrypt_map_client.keys())[list(encrypt_map_client.values()).index(vec1)]
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
            if prediction == predictor.eos_token:
                break

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

def start_client_communication_and_processing(server_ip, port=65432, dataset_path=None, java_context=None):
    global user_profile, times_dict, bytes_sent_dict, bytes_rec_list, model, predictor, encryption_helper, vertices, embedding_map, encrypt_map_client, epsilon, context, mask
    user_profile = get_graph(dataset_path, "g2")  # Use g1 as client graph prefix
    original_vertex_uris = set(v.uri for v in user_profile.vertices)

    times_dict = {}
    bytes_sent_dict = {}
    bytes_rec_list = []

    start = time.time()

    model = EmbeddingHelper(java_context)
    end = time.time()
    times_dict["Initialize Sentence Transformer"] = end-start

    start = time.time()
    # predictor = LLMPredictor(context)
    predictor = MockLLMPredictor(java_context)
    end = time.time()
    times_dict["Initialize LLM"] = end - start

    encryption_helper = Encryption()
    context = encryption_helper.get_context()
    vertices = user_profile.vertices

    start = time.time()
    embedding_map = model.encode_embedding(vertices)
    end = time.time()
    times_dict["Compute Embeddings"] = end - start

    start = time.time()
    encrypt_map_client = model.encrypt_embeddings(context, normalize = True)
    end = time.time()
    times_dict["Encryption"] = end - start

    serialized_map = {}
    global epsilon, mask
    epsilon = 0.01
    mask = get_random_mask(1, 2, False)

    serialized_encrypt_map_client = {}
    for uri, vec in encrypt_map_client.items():
        serialized_encrypt_map_client[uri] = vec.serialize()

    serialized_context = encryption_helper.serialize_context()
    serialized_map['Context'] = serialized_context
    serialized_map['Vertices'] = serialized_encrypt_map_client

    data = pickle.dumps(serialized_map)

    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.connect((server_ip, port))

            bytes_sent_dict["Context and Vertices"] = len(data)

            # logger.info(f"Sending context and vertex embeddings, total bytes: {len(data)}")
            try:
                s.sendall(data) 
                # logger.info(f"client: s.sendall() completed.")
            except Exception as e:
                logger.info(f"client: ERROR DURING SENDALL: {e}")
            s.shutdown(socket.SHUT_WR)

            try:
                start_decryption_server()
            except Exception as e:
                logger.error(f"Error in decryption server: {e}")
                # even if there is an error, continue and ensure return result
                pass

            try:
                s.settimeout(500)
                data_recv = s.recv(4)
                if data_recv:
                    bytes_rec_list.append(4)
                    msg_type = struct.unpack("!I", data_recv)[0]
            except socket.error as e:
                logger.error(f"Socket error while receiving response: {e}")
                # even if there is an error, continue and ensure return result
                pass
            except Exception as e:
                logger.error(f"Unexpected error while receiving response: {e}")
                # even if there is an error, continue and ensure return result
                pass

            print(f"Total Time: {sum_values(times_dict)}")
            print(f"Total Bytes Sent: {sum_values(bytes_sent_dict)}")
            print(f"Total Bytes Received: {sum(bytes_rec_list)}")
    except Exception as e:
        logger.error(f"Error in client communication: {e}")
        # even if there is an error, continue and ensure return result
        pass

    enriched_node_count = user_profile.get_newly_added_vertices_count(original_vertex_uris)
    return enriched_node_count


def main(dataset_path, context, server_ip, port=65432):
    start_time = time.time()

    enriched_node_count = start_client_communication_and_processing(
        server_ip=server_ip, port=port, dataset_path=dataset_path, java_context=context
    )
    
    # remove duplicate vertices before saving
    remove_duplicate_vertices_by_label_and_edge_label(user_profile)
    
    end_time = time.time()
    import os
    output_file = os.path.join(context.getFilesDir().getAbsolutePath(), "graph.json")
    user_profile.save_cytoscape_json(output_file)
    total_time = end_time - start_time
    total_bytes_received = sum(bytes_rec_list)
    return {
        "total_time": total_time,
        "total_bytes_received": total_bytes_received,
        "enriched_node_count": enriched_node_count,
        "graph_path": output_file,
    }