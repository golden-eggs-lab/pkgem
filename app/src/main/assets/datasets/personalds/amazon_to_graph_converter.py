#!/usr/bin/env python3
"""
Amazon Purchase Data to Graph Converter

This script converts Amazon purchase data from enriched_data_jun.json 
into a mobile app-friendly graph format similar to amy.json structure.

Output format:
- nodes: array of nodes with id and labels
- edges: array of edges with source, target, and labels
"""

import json
import os
from datetime import datetime
from typing import Dict, Any, List, Set
from collections import defaultdict

class AmazonToGraphConverter:
    def __init__(self):
        self.nodes = []
        self.edges = []
        self.node_id_counter = 1
        self.edge_id_counter = 1
        self.node_map = {}  # Map node content to node ID
        
    def get_or_create_node(self, content: str, labels: List[str]) -> str:
        """
        Get existing node ID or create new node
        
        Args:
            content: Node content (e.g., product name, category)
            labels: Node labels (e.g., ["Product"], ["Category"])
            
        Returns:
            Node ID string
        """
        # Clean content for better matching
        clean_content = content.strip()
        if not clean_content:
            clean_content = "Unknown"
        
        # Create a unique key for the node
        node_key = f"{clean_content}_{'_'.join(labels)}"
        
        if node_key in self.node_map:
            return self.node_map[node_key]
        
        # Create new node
        node_id = f"n{self.node_id_counter}"
        self.node_id_counter += 1
        
        # Create node with content as the first label
        node = {
            "id": node_id,
            "labels": [clean_content]
        }
        
        self.nodes.append(node)
        self.node_map[node_key] = node_id
        return node_id
    
    def add_edge(self, source_id: str, target_id: str, labels: List[str]):
        """
        Add an edge between two nodes
        
        Args:
            source_id: Source node ID
            target_id: Target node ID
            labels: Edge labels
        """
        edge = {
            "id": f"e{self.edge_id_counter}",
            "source": source_id,
            "target": target_id,
            "labels": labels
        }
        
        self.edges.append(edge)
        self.edge_id_counter += 1
    
    def extract_product_category(self, product_name: str) -> str:
        """
        Extract product category from product name using keyword matching
        
        Args:
            product_name: Product name string
            
        Returns:
            Category string
        """
        product_name_lower = product_name.lower()
        
        # Define category keywords
        categories = {
            'Electronics': ['phone', 'cable', 'usb', 'airpods', 'macbook', 'ipad', 'iphone', 'laptop', 'computer', 'docking'],
            'Kitchen': ['knife', 'blender', 'kettle', 'wok', 'pan', 'cookware', 'kitchen', 'tea kettle'],
            'Food': ['food', 'meat', 'rice', 'noodle', 'juice', 'tea', 'water', 'yogurt', 'tofu', 'cheesecake', 'bagels'],
            'Home': ['candle', 'paper', 'desk', 'chair', 'comforter', 'bed', 'window cleaner'],
            'Health': ['vitamin', 'supplement', 'medicine', 'eye drops', 'bandana', 'mask'],
            'Outdoor': ['backpack', 'boots', 'mask', 'gaitor', 'outdoor', 'neck gaiter'],
            'Office': ['office', 'desk', 'chair', 'paper', 'printer', 'study'],
            'Gift': ['gift card', 'gift'],
            'Clothing': ['socks', 'shirt', 'pants', 'shoes', 'boots'],
            'Beauty': ['candle', 'fragrance', 'personal care']
        }
        
        for category, keywords in categories.items():
            if any(keyword in product_name_lower for keyword in keywords):
                return category
        
        return 'Other'
    
    def process_purchase_record(self, record: Dict[str, Any]):
        """
        Process a single purchase record and add to graph
        
        Args:
            record: Purchase record dictionary
        """
        # Create person node (if not exists)
        person_id = self.get_or_create_node("User", ["Person"])
        
        # Create purchase episode node with detailed information
        purchase_id = record.get('purchase_id', f"purchase_{record.get('startTime', 'unknown')}")
        
        # Create purchase node with more detailed attributes
        purchase_labels = ["Purchase"]
        if record.get('type'):
            purchase_labels.append(record['type'])
        
        episode_id = self.get_or_create_node(purchase_id, purchase_labels)
        
        # Connect person to purchase
        self.add_edge(person_id, episode_id, ["made_purchase"])
        
        # Add source (Amazon)
        if record.get('source'):
            source_id = self.get_or_create_node(record['source'], ["Source"])
            self.add_edge(episode_id, source_id, ["from_source"])
        
        # Add product information with more details
        if record.get('productName'):
            # Create product node with full name
            product_id = self.get_or_create_node(record['productName'], ["Product"])
            self.add_edge(episode_id, product_id, ["involves_product"])
            
            # Add product category
            category = self.extract_product_category(record['productName'])
            category_id = self.get_or_create_node(category, ["Category"])
            self.add_edge(product_id, category_id, ["belongs_to"])
            
            # Add product price if available
            if record.get('productPrice') and record['productPrice'] != "":
                price_id = self.get_or_create_node(f"${record['productPrice']}", ["Price"])
                self.add_edge(product_id, price_id, ["has_price"])
            
            # Add product ID if available
            if record.get('productId') and record['productId'] != "":
                product_code_id = self.get_or_create_node(record['productId'], ["ProductCode"])
                self.add_edge(product_id, product_code_id, ["has_code"])
            
            # Add quantity information
            if record.get('productQuantity') and record['productQuantity'] != "" and record['productQuantity'] != "0":
                quantity_id = self.get_or_create_node(f"Qty: {record['productQuantity']}", ["Quantity"])
                self.add_edge(episode_id, quantity_id, ["with_quantity"])
        
        # Add time information with more granularity
        if record.get('startTime'):
            try:
                dt = datetime.fromisoformat(record['startTime'].replace('Z', '+00:00'))
                date_str = dt.strftime('%Y-%m-%d')
                month_str = dt.strftime('%Y-%m')
                year_str = dt.strftime('%Y')
                time_str = dt.strftime('%H:%M')
                
                # Add specific time
                time_id = self.get_or_create_node(time_str, ["TimeOfDay"])
                self.add_edge(episode_id, time_id, ["at_time"])
                
                # Add date node
                date_id = self.get_or_create_node(date_str, ["Date"])
                self.add_edge(episode_id, date_id, ["on_date"])
                
                # Add month node
                month_id = self.get_or_create_node(month_str, ["Month"])
                self.add_edge(date_id, month_id, ["in_month"])
                
                # Add year node
                year_id = self.get_or_create_node(year_str, ["Year"])
                self.add_edge(month_id, year_id, ["in_year"])
                
            except (ValueError, TypeError):
                # If date parsing fails, just add the raw string
                time_id = self.get_or_create_node(record['startTime'], ["Time"])
                self.add_edge(episode_id, time_id, ["at_time"])
        
        # Add additional purchase details
        if record.get('purchase_id') and record['purchase_id'] != "":
            purchase_code_id = self.get_or_create_node(record['purchase_id'], ["PurchaseCode"])
            self.add_edge(episode_id, purchase_code_id, ["has_purchase_id"])
        
        # Add currency information if available
        if record.get('currency') and record['currency'] != "":
            currency_id = self.get_or_create_node(record['currency'], ["Currency"])
            self.add_edge(episode_id, currency_id, ["in_currency"])
        
        # Add outdoor indicator if available
        if record.get('outdoor') is not None:
            outdoor_status = "Outdoor" if record['outdoor'] == 1 else "Indoor"
            outdoor_id = self.get_or_create_node(outdoor_status, ["Environment"])
            self.add_edge(episode_id, outdoor_id, ["in_environment"])
    
    def convert_data(self, input_file: str, output_file: str):
        """
        Convert Amazon data to graph format
        
        Args:
            input_file: Path to input JSON file
            output_file: Path to output JSON file
        """
        print(f"Loading data from {input_file}...")
        
        # Load input data
        with open(input_file, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        print(f"Loaded {len(data)} purchase records")
        
        # Process each record
        for i, record in enumerate(data):
            if i % 100 == 0:
                print(f"Processing record {i+1}/{len(data)}...")
            self.process_purchase_record(record)
        
        # Create output structure
        output_data = {
            "nodes": self.nodes,
            "edges": self.edges
        }
        
        # Save output
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(output_data, f, indent=2, ensure_ascii=False)
        
        print(f"\nConversion completed!")
        print(f"Created {len(self.nodes)} nodes and {len(self.edges)} edges")
        print(f"Output saved to {output_file}")
        
        # Print some statistics
        self.print_statistics()
    
    def print_statistics(self):
        """Print graph statistics"""
        # Count nodes by type
        node_types = defaultdict(int)
        for node in self.nodes:
            for label in node['labels']:
                node_types[label] += 1
        
        print("\nNode Statistics:")
        for node_type, count in sorted(node_types.items()):
            print(f"  {node_type}: {count}")
        
        # Count edges by type
        edge_types = defaultdict(int)
        for edge in self.edges:
            for label in edge['labels']:
                edge_types[label] += 1
        
        print("\nEdge Statistics:")
        for edge_type, count in sorted(edge_types.items()):
            print(f"  {edge_type}: {count}")


def main():
    """Main function"""
    # File paths
    input_file = "enriched_data_jun.json"
    output_file = "amazon_purchases_graph.json"
    
    # Check if input file exists
    if not os.path.exists(input_file):
        print(f"Error: Input file '{input_file}' not found!")
        print("Please make sure the file is in the same directory as this script.")
        return
    
    # Create converter and process data
    converter = AmazonToGraphConverter()
    converter.convert_data(input_file, output_file)
    
    print(f"\n✅ Successfully converted {input_file} to {output_file}")
    print("The output file is now ready for mobile app consumption!")


if __name__ == "__main__":
    main()
