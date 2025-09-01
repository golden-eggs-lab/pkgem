# define several configuration parameters for global experiment
class ConfigManager:
    _instance = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(ConfigManager, cls).__new__(cls)
            cls._instance._initialize()
        return cls._instance
    
    def _initialize(self):
        # Default configuration
        self._config = {
            "enrichment": {
                "enabled": True,
                "security_mode": True,  # True for secure mode, False for plaintext mode
                "sigma": 0.85,    # vertex similarity threshold
                "theta": 2.4,     # path similarity threshold
                "k": 3           # top-k paths
            },
            "model": {
                "name": "TinyLlama-1.1B-Chat-v1.0",
                "device": "auto"
            },
            "network": {
                "host": "127.0.0.1",
                "port": 65432
            }
        }
        
        # Plaintext mode configuration (used when security_mode is False)
        self._plaintext_config = {
            "enrichment": {
                "enabled": True,
                "security_mode": False,
                "sigma": 0.85,
                "theta": 2.4,
                "k": 3
            },
            "model": {
                "name": "distilgpt2",
                "device": "auto"
            },
            "network": {
                "host": "127.0.0.1",
                "port": 65432
            }
        }
    
    def set_security_mode(self, enabled: bool):
        """Switch between secure and plaintext modes"""
        if enabled:
            self._config["enrichment"]["security_mode"] = True
        else:
            self._config["enrichment"]["security_mode"] = False
    
    def is_security_mode(self) -> bool:
        """Check if security mode is enabled"""
        return self._config["enrichment"]["security_mode"]
    
    def get_config(self) -> dict:
        """Get current configuration"""
        return self._config
    
    def update_config(self, new_config: dict):
        """Update configuration with new values"""
        self._config.update(new_config)
    
    def get_enrichment_config(self) -> dict:
        """Get enrichment specific configuration"""
        return self._config["enrichment"]
    
    def get_model_config(self) -> dict:
        """Get model specific configuration"""
        return self._config["model"]
    
    def get_network_config(self) -> dict:
        """Get network specific configuration"""
        return self._config["network"]
    
    @property
    def sigma(self) -> float:
        return self._config["enrichment"]["sigma"]
    
    @property
    def theta(self) -> float:
        return self._config["enrichment"]["theta"]
    
    @property
    def k(self) -> int:
        return self._config["enrichment"]["k"]
    
    @property
    def security_mode(self) -> bool:
        return self._config["enrichment"]["security_mode"]
    
    @property
    def enrichment_enabled(self) -> bool:
        return self._config["enrichment"]["enabled"]
    
    @property
    def model_name(self) -> str:
        return self._config["model"]["name"]
    
    @property
    def device(self) -> str:
        return self._config["model"]["device"]
    
    @property
    def host(self) -> str:
        return self._config["network"]["host"]
    
    @property
    def port(self) -> int:
        return self._config["network"]["port"]

# Create a global config instance
config = ConfigManager()

