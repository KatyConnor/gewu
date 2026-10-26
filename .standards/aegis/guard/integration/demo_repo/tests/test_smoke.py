import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import token                                        # noqa: E402


def test_verify_ok():
    p = token.verify("h.p.u1", "k", alg="HS256")
    assert p == {"sub": "u1"}


if __name__ == '__main__':
    test_verify_ok()
    print("GREEN")
