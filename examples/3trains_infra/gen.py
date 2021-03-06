import os, sys, inspect
current_dir = os.path.dirname(os.path.abspath(inspect.getfile(inspect.currentframe())))
parent_dir = os.path.dirname(current_dir)
grand_parent_dir = os.path.dirname(parent_dir)
sys.path.insert(0, grand_parent_dir)
import examples_generator.libgen as gen

L = 1000 * 2 ** -0.5

# build the network
infra = gen.Infra([1000] * 7)
infra.add_switch(2, 0, 1, 0, 0)
infra.add_switch(2, 3, 6, 0, 1000)
infra.add_switch(3, 4, 5, L, 1000 + L)

infra.set_buffer_stop_coordinates(0, -L, -L)
infra.set_buffer_stop_coordinates(1, L, -L)
infra.set_buffer_stop_coordinates(6, -L, 1000 + L)
infra.set_buffer_stop_coordinates(4, 0, 1000 + 2 * L)
infra.set_buffer_stop_coordinates(5, 2 * L, 1000 + 2 * L)

# build the trains
sim = gen.Simulation(infra)
sim.add_schedule(0, 4, 1)
sim.add_schedule(0, 0, 5)
sim.add_schedule(0, 1, 6)

# build the successions
succession = gen.Succession()
succession.add_table(2, 0, 1, [2, 0, 1])
succession.add_table(2, 3, 6, [2, 0, 1])
succession.add_table(3, 4, 5, [0, 1])

gen.write_json("config.json", gen.CONFIG_JSON)
gen.write_json("infra.json", infra.to_json())
gen.write_json("simulation.json", sim.to_json())
gen.write_json("succession.json", succession.to_json())
