from typing import Dict, Any
import numpy as np
import traceback

# Global state to track enrichment status
enrichment_status = {
    "status": "initializing",
    "total_time": 0.0,
    "total_bytes_received": 0,
    "enriched_node_count": 0,
    "result_file": "",
    "error": None,
    "is_running": True,
    "security_mode": False
}

# def update_progress(progress: int):
#     """Update the enrichment progress (0-100)"""
#     enrichment_status["progress"] = min(max(progress, 0), 100)

def save_enrichment_result(result: dict, context) -> str:
    """Save enrichment result to a file and return the file path"""
    import json
    import os
    
    # Create results directory if it doesn't exist
    results_dir = os.path.join(context.getFilesDir().getAbsolutePath(), "results")
    os.makedirs(results_dir, exist_ok=True)
    
    # Save result to file
    result_file = os.path.join(results_dir, "enrich_result.json")
    with open(result_file, 'w') as f:
        json.dump(result, f)
    
    return result_file

def stop_enrichment_server():
    """Stop the enrichment server"""
    enrichment_status["is_running"] = False
    enrichment_status["status"] = "Stopping server..."

    enrichment_status["status"] = "Server stopped"

def _call_kotlin_progress_callback(callback_obj, progress_percentage: int, status_message: str):
    """Safely call Kotlin progress callback"""
    if callback_obj and hasattr(callback_obj, "onProgressUpdate"):
        try:
            callback_obj.onProgressUpdate(progress_percentage, status_message)
        except Exception as e:
            print(f"PY_ERROR: Failed to call Kotlin onProgressUpdate: {e}")

def _get_security_mode_from_prefs(context) -> bool:
    """Get security mode from Android SharedPreferences"""
    try:
        prefs = context.getSharedPreferences("settings", 0)  # MODE_PRIVATE = 0
        return prefs.getBoolean("security_mode", False)
    except Exception as e:
        print(f"Error reading security mode from prefs: {e}")
        return False

def run_enrichment_server_wrapper(host: str, port: int, dataset_path: str, 
                                progress_callback: object,
                                android_context_obj) -> dict:
    global enrichment_status
    enrichment_status = {
        "status": "initializing",
        "total_time": 0.0,
        "total_bytes_received": 0,
        "enriched_node_count": 0,
        "result_file": "",
        "error": None,
        "is_running": True,
        "security_mode": _get_security_mode_from_prefs(android_context_obj)
    }
    
    _call_kotlin_progress_callback(progress_callback, 0, "Server: Initializing...")
    
    try:
        from server import main as server_main
        from server_unencrypted import main as server_main_unencrypted

        server_result_data = None

        if enrichment_status["security_mode"]:
            server_result_data = server_main(
                dataset_path=dataset_path,
                java_context=android_context_obj,
                decryption_host=host,
                port=port,
                progress_callback=progress_callback
            )
        else:
            server_result_data = server_main_unencrypted(
                dataset_path=dataset_path,
                java_context=android_context_obj,
                decryption_host=host,
                port=port,
                progress_callback=progress_callback
            )
        
        if server_result_data:
            enrichment_status["total_time"] = server_result_data.get("total_time", 0.0)
            enrichment_status["total_bytes_received"] = server_result_data.get("total_bytes_received", 0)
            enrichment_status["enriched_node_count"] = server_result_data.get("enriched_node_count", 0)
            # enrichment_status["result_file"] = save_enrichment_result(server_result_data.get("actual_data"), android_context_obj)
            # Load graph data from graph.json
            enrichment_status["result_file"] = server_result_data.get("graph_path", "")

            enrichment_status["status"] = "Server completed successfully"
            _call_kotlin_progress_callback(progress_callback, 100, "Server: Completed successfully")
        
        
    except Exception as e:
        error_msg = str(e)
        print(f"PY_ERROR: Server enrichment failed: {error_msg}")
        print(traceback.format_exc())  # 打印完整堆栈
        enrichment_status["error"] = error_msg
        _call_kotlin_progress_callback(progress_callback, 100, f"Server: Failed - {error_msg[:50]}...")
    finally:
        enrichment_status["is_running"] = False
    
    return enrichment_status

def run_enrichment_client_wrapper(server_ip: str, server_port: int, dataset_path: str,
                                progress_callback: object,
                                android_context_obj) -> dict:
    global enrichment_status
    enrichment_status = {
        "status": "initializing",
        "total_time": 0.0,
        "total_bytes_received": 0,
        "enriched_node_count": 0,
        "result_file": "",
        "error": None,
        "is_running": True,
        "security_mode": _get_security_mode_from_prefs(android_context_obj)
    }
    
    _call_kotlin_progress_callback(progress_callback, 0, "Client: Initializing...")
    
    try:
        from client import main as client_main
        from client_unencrypted import main as client_main_unencrypted

        client_result_data = None

        _call_kotlin_progress_callback(progress_callback, 32, "Client: Computing...")

        if enrichment_status["security_mode"]:
            client_result_data = client_main(
                dataset_path=dataset_path,
                context=android_context_obj,
                server_ip=server_ip,
                port=server_port
            )
        else:   
            client_result_data = client_main_unencrypted(
                dataset_path=dataset_path,
                context=android_context_obj,
                server_ip=server_ip,
                port=server_port
            )
            
        _call_kotlin_progress_callback(progress_callback, 66, "Client: Computing...")

        if client_result_data:
            enrichment_status["total_time"] = client_result_data.get("total_time", 0.0)
            enrichment_status["total_bytes_received"] = client_result_data.get("total_bytes_received", 0)
            enrichment_status["enriched_node_count"] = client_result_data.get("enriched_node_count", 0)
            enrichment_status["result_file"] = client_result_data.get("graph_path", "")
            if client_result_data.get("error"):
                enrichment_status["error"] = client_result_data["error"]
                enrichment_status["status"] = f"Client failed: {client_result_data['error']}"
            else:
                enrichment_status["status"] = "Client completed successfully"
        _call_kotlin_progress_callback(progress_callback, 100, "Client: Completed successfully")
        
    except Exception as e:
        error_msg = str(e)
        print(f"PY_ERROR: Client enrichment failed: {error_msg}")
        print(traceback.format_exc())  # 打印完整堆栈
        enrichment_status["error"] = error_msg
        _call_kotlin_progress_callback(progress_callback, 100, f"Client: Failed - {error_msg[:50]}...")
    finally:
        enrichment_status["is_running"] = False
    
    return enrichment_status
