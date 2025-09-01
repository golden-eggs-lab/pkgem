from com.example.pkgenrich.utils import TextEmbedder
from typing import List
import numpy as np
from java.util import ArrayList

class EmbeddingBridge:
    def __init__(self, context):
        self.embedder = TextEmbedder.getInstance(context)
    
    def encode(self, text: str) -> np.ndarray:
        """
        Encode a single text using TextEmbedder
        """
        embedding = self.embedder.encode(text)
        if embedding is None:
            raise ValueError(f"Failed to encode text: {text}")
        return np.array(embedding)
    
    def encode_batch(self, texts: List[str]) -> List[np.ndarray]:
        """
        Encode a batch of texts using TextEmbedder
        """
        java_list = ArrayList()
        for t in texts:
            java_list.add(t)
        result = self.embedder.encodeBatch(java_list)
        return [np.array(result.get(i)) for i in range(result.size())] 