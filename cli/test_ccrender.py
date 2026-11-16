import base64
import io
import json
import tempfile
import unittest
from pathlib import Path
from PIL import Image
import ccrender as cli


class CLITests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = Path(self.dir.name)

    def test_comma_and_multiline_csv(self):
        p = self.root/'cards.csv'
        p.write_text('Badge Value,Title,Description\n"3,000","One, two","line 1\nline 2"\n', encoding='utf-8')
        project = cli.load_project(p)
        self.assertEqual(len(project.cards), 1)
        self.assertEqual(project.cards[0].badge, '3,000')
        self.assertIn('\n', project.cards[0].description)

    def test_v5_project_and_embedded_artwork(self):
        buf = io.BytesIO()
        Image.new('RGBA',(28,28),(220,10,20,255)).save(buf, format='PNG')
        p = self.root/'sample.ccv5'
        p.write_text(json.dumps({'FormatVersion':5,'Cards':[{'Title':'Test','Badge':'9','ArtworkBase64':base64.b64encode(buf.getvalue()).decode(), 'ImageTransform': {'Scale':1.4,'Rotation':15}}]}))
        project = cli.load_project(p)
        image=cli.render_frame(project, 1.5, cli.Assets())
        self.assertEqual(image.size,(1920,1080))
        self.assertNotEqual(image.getpixel((200,540)), cli.BACK)

    def test_custom_duration_rescales_motion(self):
        p=cli.Project([cli.Card(title=str(i)) for i in range(7)], duration=2.1, width=640, height=360)
        # By the final half-second the last card must have reached the viewport.
        auto=2.3+(len(p.cards)-4)*p.seconds_per_card+1.8
        model_t=(p.duration-.4)*auto/p.duration
        scroll=(model_t-2.3)/p.seconds_per_card
        self.assertGreater(scroll,2)
        frame=cli.render_frame(p,p.duration-.4,cli.Assets())
        self.assertEqual(frame.mode,'RGB')

    def test_invalid_resolution_rejected(self):
        with self.assertRaises(cli.CLIError):
            cli.check(cli.Project([cli.Card(title='T')],width=641))

    def test_invalid_artwork_fails(self):
        p=cli.Project([cli.Card(artwork=str(self.root/'none.png'))])
        with self.assertRaises(cli.CLIError):
            cli.Assets().load(p.cards[0])

    def test_render_preview_and_video(self):
        p=self.root/'cards.csv'
        p.write_text('Title,Badge Value\nOne,1\nTwo,2\n')
        self.assertEqual(cli.main(['preview',str(p),'-o',str(self.root/'frame.png'),'--width','640','--height','360']),0)
        self.assertTrue((self.root/'frame.png').is_file())
        self.assertEqual(cli.main(['render',str(p),'-o',str(self.root/'movie.mp4'),'--width','640','--height','360','--fps','8','--duration','1.0']),0)
        self.assertGreater((self.root/'movie.mp4').stat().st_size,1000)


if __name__ == '__main__':
    unittest.main()