#!/usr/bin/python
# -*- coding:utf-8 -*-
from __future__ import print_function, division

import sys
import os
import glob
import itertools
import argparse
import subprocess
import numpy
import tables

parser = argparse.ArgumentParser()
parser.add_argument('file', type=tables.openFile)
parser.add_argument('--save')
parser.add_argument('--connections', action='store_true')
parser.add_argument('--reactions', action='store_true')
parser.add_argument('--particles', action='store_true')
parser.add_argument('--stimulation', action='store_true')
parser.add_argument('--reaction', action='store_true')
parser.add_argument('--diffusion', action='store_true')

class Drawer(object):
    def __init__(self, f, ax, species, times, data, title=''):
        from matplotlib.colors import LogNorm

        N = numpy.arange(species.shape[0])
        V = numpy.arange(data.shape[0])
        self.data = data
        self.times = times
        self.title = title
        self.ax = ax

        ax.set_xlabel("species")
        ax.xaxis.set_ticks(N + 0.5)
        ax.xaxis.set_ticklabels(species, rotation=70)
        ax.set_ylabel("voxel#")
        # matplotlib gets confused if we draw all zeros
        initial = data.sum(axis=(1,2)).argmax()
        if data[initial].max() == 0:
            # work around colorbar issues
            data[initial, 0, 0] = 1
        self.image = ax.imshow(data[initial], origin='lower',
                               extent=(0, data.shape[2], 0, data.shape[1]),
                               interpolation='spline16', aspect='auto',
                               norm=LogNorm())
        f.colorbar(self.image)

    def update(self, i):
        self.ax.set_title("{}   step {:>3}, t = {:8.4f} ms"
                          .format(self.title, i, self.times[i]))
        self.image.set_data(self.data[i])

class DrawerSet(object):
    def __init__(self, model, sim, opts):
        from matplotlib import pyplot
        pyplot.ion()

        self.save = opts.save

        self.figure = f = pyplot.figure(figsize=(12, 9))
        f.clear()

        self.drawers = []
        species = model.species
        times = sim.times
        num = opts.particles + opts.stimulation + opts.diffusion + opts.reaction
        shape = [(1,1), (2,1), (2,2), (2,2)][num - 1]
        pos = 1

        if opts.particles:
            ax = f.add_subplot(shape[0], shape[1], pos)
            data = sim.concentrations[:]
            self.drawers += [Drawer(f, ax, species, times, data,
                                    title='Particle numbers')]
            pos += 1
        if opts.stimulation:
            ax = f.add_subplot(shape[0], shape[1], pos)
            data = sim.stimulation_events[:]
            self.drawers += [Drawer(f, ax, species, times, data,
                                    title='Stimulation events')]
            pos += 1
        if opts.diffusion:
            ax = f.add_subplot(shape[0], shape[1], pos)
            # reduce dimensionality by summing over all neighbours
            data = sim.diffusion_events[:].sum(axis=-1)
            self.drawers += [Drawer(f, ax, species, times, data,
                                    title='Diffusion events')]
            pos += 1
        if opts.reaction:
            ax = f.add_subplot(shape[0], shape[1], pos)
            data = sim.reaction_events[:]
            self.drawers += [Drawer(f, ax, species, times, data,
                                    title='Reaction events')]

        f.tight_layout()
        if not opts.save:
            f.show()

        items = range(data.shape[0])
        if opts.save:
            self.range = items
        else:
            self.range = itertools.cycle(items)

    def animate(self):
        for i in self.range:
            for drawer in self.drawers:
                drawer.update(i)
            if not self.save:
                self.figure.canvas.draw()
            else:
                self.figure.savefig('{}-{:06d}.png'.format(self.save, i))
                print('.', end='')
                sys.stdout.flush()

def make_movie(save):
    command = '''mencoder -mf type=png:w=800:h=600:fps=25
                 -ovc lavc -lavcopts vcodec=mpeg4 -oac copy -o'''.split()
    command += [save, 'mf://*.png'.format(save)]
    print("running {}", command)
    subprocess.check_call(command)

def _conn(dst, a, b, penwidth=None):
    w = ' [penwidth={}]'.format(penwidth) if penwidth is not None else ''
    print('\t"{}" -> "{}"{};'.format(a, b, w), file=dst)

def _connections(dst, connections, couplings):
    print('digraph Connections {', file=dst)
    print('\trankdir=LR;', file=dst)
    print('\tsplines=true;', file=dst)
    print('\tnode [color=blue,style=filled,fillcolor=lightblue];', file=dst)
    for i in range(connections.shape[0]):
        for j, coupl in zip(connections[i], couplings[i]):
            if j < 0:
                break
            coupl = min(max(numpy.log(coupl)+3, 0.3), 5)
            _conn(dst, i, j, coupl)
    print('}', file=dst)

def dot_connections(model):
    _connections(sys.stdout, model.neighbors, model.couplings)

def _reaction_name(rr, rr_s, pp, pp_s, species):
    return ' ⇌ '.join(
        ('+'.join('{}{}'.format(s if s > 1 else '', species[r])
                  for r, s in zip(rr_, ss_)
                  if r >= 0)
         for rr_, ss_ in ((rr, rr_s), (pp, pp_s))))

def _productions(dst, species, reactants, r_stochio, products, p_stochio):
    print('digraph Reactions {', file=dst)
    print('\trankdir=LR;', file=dst)
    print('\tsplines=true;', file=dst)
    print('\tnode [color=green,style=filled,fillcolor=lightgreen];', file=dst)
    for rr, rr_s, pp, pp_s in zip(reactants, r_stochio,
                                  products, p_stochio):
        name = _reaction_name(rr, rr_s, pp, pp_s, species)
        print('\t"{}" [color=black,shape=point,fillcolor=magenta];'.format(name))
        for j, s in zip(rr, rr_s):
            if j < 0:
                break
            _conn(dst, species[j], name)
        for j, s in zip(pp, pp_s):
            if j < 0:
                break
            _conn(dst, name, species[j])
        print()
    print('}', file=dst)

def dot_productions(model):
    _productions(sys.stdout, model.species,
                 model.reactions.reactants, model.reactions.reactant_stochiometry,
                 model.reactions.products, model.reactions.product_stochiometry)

if __name__ == '__main__':
    opts = parser.parse_args()
    if opts.connections:
        dot_connections(opts.file.root.model)
    elif opts.reactions:
        dot_productions(opts.file.root.model)
    else:
        import matplotlib
        if opts.save:
            matplotlib.use('Agg')

        ss = DrawerSet(opts.file.root.model, opts.file.root.simulation, opts)
        ss.animate()

        if opts.save:
            make_movie(opts.save)
            for fname in glob.glob('{}-*.png'.format(opts.save)):
                os.unlink(fname)
