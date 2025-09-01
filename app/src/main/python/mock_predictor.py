from typing import List

class MockLLMPredictor:
    def __init__(self, context=None):
        """
        Initialize the mock predictor.
        Args:
            context: Not used in mock implementation
        """
        self.eos_token = "<|endoftext|>"
        self.max_depth = 3  # Default max depth for path search

    def predict(self, input_word: str, candidate_words: List[str]) -> str:
        """
        Mock predict method that implements depth-first search for finding paths.
        Args:
            input_word: The input word (not used in mock)
            candidate_words: List of candidate words
        Returns:
            str: The next word in the path, or EOS token if max depth reached
        """
        if not candidate_words:
            return self.eos_token
            
        # If we have candidates, return the first one
        # This simulates finding a path of the specified depth
        return candidate_words[0] 