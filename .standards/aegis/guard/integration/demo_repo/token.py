"""JWT 验签入口（演示仓库）——缺陷版本: verify 未校验 alg（CWE-347）"""


class InvalidAlgorithm(Exception):
    pass


def verify(token, key, alg):
    payload = _verify_sig(token, key, alg)
    return payload


def _verify_sig(token, key, alg):
    if token.count(".") != 2:
        raise ValueError("malformed token")
    return {"sub": token.split(".")[2]}
