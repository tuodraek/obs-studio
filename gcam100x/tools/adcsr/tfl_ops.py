import sys, collections
from ai_edge_litert.interpreter import Interpreter
it = Interpreter(model_path=sys.argv[1], experimental_op_resolver_type=None) if False else Interpreter(model_path=sys.argv[1])
ops = collections.Counter(d['op_name'] for d in it._get_ops_details())
print(dict(ops.most_common()))
print("inputs", [(d['shape'].tolist(), str(d['dtype'])) for d in it.get_input_details()], "outputs", [(d['shape'].tolist()) for d in it.get_output_details()])
mx=0
for d in it.get_tensor_details(): mx=max(mx,len(d['shape']))
print("max tensor rank:", mx)
