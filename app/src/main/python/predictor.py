from typing import List

class MockLLMPredictor:
    def __init__(self, context=None):
        """
        Initialize the mock predictor.
        Args:
            context: Not used in mock implementation
        """
        pass

    def predict(self, input_word: str, candidate_words: List[str]) -> str:
        """
        Mock predict method that always returns all candidate words.
        Args:
            input_word: The input word (not used in mock)
            candidate_words: List of candidate words
        Returns:
            str: All candidate words joined with spaces
        """
        return " ".join(candidate_words) 