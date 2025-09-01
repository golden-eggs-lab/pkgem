import tenseal as ts


class Encryption:
    def __init__(self):
        self.context = ts.context(
            ts.SCHEME_TYPE.CKKS,
            poly_modulus_degree=8192,
            coeff_mod_bit_sizes= [60, 30, 30, 30, 60]
        )
        self.context.generate_galois_keys()
        self.context.global_scale = 2 ** 30

    def get_context(self) -> ts.Context:
        return self.context

    def serialize_context(self) -> bytes:
        return self.context.serialize(
                save_public_key=True,
                save_secret_key=False,   # or False, if you don't want to share it
                save_galois_keys=True,
                save_relin_keys=True
                )