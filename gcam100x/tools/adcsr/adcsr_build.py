# Builds the AdcSR inference network exactly as the official test.py does, but
# without downloading Stable Diffusion: every weight is overwritten by the
# AdcSR checkpoint (strict load), so only the SD 2.1-base UNet *architecture* is needed.
import sys, copy, torch
REPO = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith('-') else 'repo'
sys.path.insert(0, REPO)
from diffusers import UNet2DConditionModel
from diffusers.models.autoencoders.vae import Decoder
SD21_BASE_UNET = dict(sample_size=64, in_channels=4, out_channels=4, center_input_sample=False, flip_sin_to_cos=True,
    freq_shift=0, down_block_types=["CrossAttnDownBlock2D"]*3+["DownBlock2D"], up_block_types=["UpBlock2D"]+["CrossAttnUpBlock2D"]*3,
    block_out_channels=[320,640,1280,1280], layers_per_block=2, downsample_padding=1, mid_block_scale_factor=1, act_fn="silu",
    norm_num_groups=32, norm_eps=1e-5, cross_attention_dim=1024, attention_head_dim=[5,10,20,20], use_linear_projection=True,
    dual_cross_attention=False)
def build(repo=REPO):
    from model import Net
    unet = UNet2DConditionModel(**SD21_BASE_UNET)
    decoder = Decoder(in_channels=4, out_channels=3, up_block_types=["UpDecoderBlock2D"]*4, block_out_channels=[64,128,256,256],
        layers_per_block=2, norm_num_groups=32, act_fn="silu", norm_type="group", mid_block_add_attention=True)
    ck = torch.load(f"{repo}/weight/pretrained/halfDecoder.ckpt", weights_only=False, map_location="cpu")
    decoder.load_state_dict({k.replace("decoder.", ""): v for k, v in ck["state_dict"].items() if "decoder" in k}, strict=True)
    model = torch.nn.DataParallel(Net(unet, copy.deepcopy(decoder)))
    model.load_state_dict(torch.load(f"{repo}/weight/net_params_200.pkl", weights_only=False, map_location="cpu"), strict=True)
    return torch.nn.Sequential(model.module, *decoder.up_blocks, decoder.conv_norm_out, decoder.conv_act, decoder.conv_out).eval()
